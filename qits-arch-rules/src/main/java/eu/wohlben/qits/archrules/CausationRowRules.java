package eu.wohlben.qits.archrules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * The completeness guard for causation-traced rows: every entity must <em>decide</em>, so that
 * "forgot to participate" fails a build instead of leaving a silent hole in the trace.
 *
 * <p>qits-eventstream's row stamping is opt-in per entity — implement {@code CausedRow}, attach
 * {@code @EntityListeners(CausationStamp.class)} — and an entity that does neither is not an error
 * at runtime, which is exactly the problem: the trace just misses its rows, and nothing says so.
 * These rules turn the gap into a failed test and the deliberate opt-out into one reviewable
 * {@code @Uncaused} line.
 *
 * <p>Enable them in a service with a test-scope dependency on this module and:
 *
 * <pre>{@code
 * @AnalyzeClasses(packages = "eu.wohlben.qits.myservice",
 *     importOptions = ImportOption.DoNotIncludeTests.class)
 * class ArchRulesTest {
 *   @ArchTest static final ArchTests CAUSATION = ArchTests.in(CausationRowRules.class);
 * }
 * }</pre>
 *
 * <p><b>Every judged type is named as a string, never imported.</b> A dependency on
 * qits-eventstream would need the platform registry (this repo builds from a bare clone) and would
 * couple two libraries' versions for nothing — bytecode carries names, and names are what ArchUnit
 * matches. The contract that follows: these constants must track the real types, and the fixture
 * mirror in this module's own suite is where a drift surfaces. One consequence to know:
 * {@code CausedRow} is matched through the class hierarchy ArchUnit imported, so an entity that
 * inherits the interface from a superclass outside the analyzed packages needs that superclass on
 * the import path — implementing it directly, as the {@code CausedRow} javadoc shows, never has
 * the problem.
 */
public final class CausationRowRules {

  static final String ENTITY = "jakarta.persistence.Entity";
  static final String ENTITY_LISTENERS = "jakarta.persistence.EntityListeners";
  static final String CAUSED_ROW = "eu.wohlben.qits.eventstream.CausedRow";
  static final String CAUSATION_STAMP = "eu.wohlben.qits.eventstream.CausationStamp";
  static final String UNCAUSED = "eu.wohlben.qits.eventstream.Uncaused";

  private CausationRowRules() {}

  // Every rule carries allowEmptyShould(true), and the reason is the same for all three: a match
  // set that comes up empty is the BEST state a rule describes, not a misconfigured rule. Measured
  // on qits-projects' epics module, 2026-08-10: all four entities participated, nothing was
  // @Uncaused, and ArchUnit's fail-on-empty default turned the ideal package into a red build.
  // A repo with no entities at all earns the same pass from the other two rules.

  /** The decision rule: participate or opt out, in writing. */
  @ArchTest
  public static final ArchRule everyEntityDecidesAboutCausation =
      classes()
          .that()
          .areAnnotatedWith(ENTITY)
          .should(implementCausedRowOrDeclareUncaused())
          .because(
              "an entity that silently skips causation is a hole in the trace nothing reports;"
                  + " implement CausedRow or declare @Uncaused where a reviewer reads it")
          .allowEmptyShould(true);

  /**
   * The wiring rule: an entity that implements the interface without attaching the listener is the
   * quietest failure of all — it looks like it participates and stamps nothing.
   */
  @ArchTest
  public static final ArchRule everyCausedRowAttachesTheStamp =
      classes()
          .that()
          .areAnnotatedWith(ENTITY)
          .and()
          .implement(CAUSED_ROW)
          .should(listEntityListener(CAUSATION_STAMP))
          .because("CausedRow without @EntityListeners(CausationStamp.class) stamps nothing")
          .allowEmptyShould(true);

  /** The contradiction guard: a class cannot both participate and claim it does not. */
  @ArchTest
  public static final ArchRule uncausedMeansUncaused =
      noClasses()
          .that()
          .areAnnotatedWith(UNCAUSED)
          .should()
          .implement(CAUSED_ROW)
          .because("@Uncaused on a CausedRow contradicts itself; drop one")
          .allowEmptyShould(true);

  private static ArchCondition<JavaClass> implementCausedRowOrDeclareUncaused() {
    return new ArchCondition<>(
        "implement " + CAUSED_ROW + " or be annotated with @" + UNCAUSED) {
      @Override
      public void check(JavaClass entity, ConditionEvents events) {
        boolean participates = entity.isAssignableTo(CAUSED_ROW);
        boolean optedOut = entity.isAnnotatedWith(UNCAUSED);
        if (!participates && !optedOut) {
          events.add(
              SimpleConditionEvent.violated(
                  entity,
                  entity.getName() + " neither implements CausedRow nor declares @Uncaused"));
        }
      }
    };
  }

  private static ArchCondition<JavaClass> listEntityListener(String listenerName) {
    return new ArchCondition<>("be annotated with @EntityListeners naming " + listenerName) {
      @Override
      public void check(JavaClass entity, ConditionEvents events) {
        boolean stamped =
            entity
                .tryGetAnnotationOfType(ENTITY_LISTENERS)
                .flatMap(annotation -> annotation.get("value"))
                .map(value -> names(value, listenerName))
                .orElse(false);
        if (!stamped) {
          events.add(
              SimpleConditionEvent.violated(
                  entity,
                  entity.getName() + " implements CausedRow but does not list " + listenerName));
        }
      }
    };
  }

  private static boolean names(Object listeners, String listenerName) {
    if (!(listeners instanceof Object[] array)) {
      return false;
    }
    for (Object listener : array) {
      if (listener instanceof JavaClass type && type.getName().equals(listenerName)) {
        return true;
      }
    }
    return false;
  }
}
