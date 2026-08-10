package eu.wohlben.qits.archrules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

/**
 * The rules against their own fixtures: a clean package passes without a word, and each way of
 * getting it wrong is caught by the rule that owns it — named in the violation, so a service
 * reading a red build knows which entity and which repair.
 *
 * <p>Programmatic {@code evaluate} rather than the {@code @ArchTest} runner, because half the
 * assertions are that a rule <em>fails</em>, and the runner treats a failing rule as a failing
 * test. Consumers use the runner; the snippet is in {@link CausationRowRules}'s javadoc.
 */
class CausationRowRulesTest {

  private static final JavaClasses GOOD =
      new ClassFileImporter().importPackages("eu.wohlben.qits.archrules.fixtures.good");
  private static final JavaClasses FORGOTTEN =
      new ClassFileImporter().importPackages("eu.wohlben.qits.archrules.fixtures.forgotten");

  private static final JavaClasses ENTITYLESS =
      new ClassFileImporter().importPackages("eu.wohlben.qits.archrules.fixtures.entityless");

  @Test
  void aPackageWhereEveryEntityDecidedPassesAllThreeRules() {
    assertClean(CausationRowRules.everyEntityDecidesAboutCausation, GOOD);
    assertClean(CausationRowRules.everyCausedRowAttachesTheStamp, GOOD);
    assertClean(CausationRowRules.uncausedMeansUncaused, GOOD);
  }

  /**
   * An empty match set is the BEST state a rule describes — every entity participating leaves the
   * opt-out rules nothing to look at, and that must pass, not fail as a "misconfigured" rule.
   * Measured on qits-projects' epics module before {@code allowEmptyShould} joined the rules.
   */
  @Test
  void aPackageWithNothingToJudgePassesAllThreeRules() {
    assertClean(CausationRowRules.everyEntityDecidesAboutCausation, ENTITYLESS);
    assertClean(CausationRowRules.everyCausedRowAttachesTheStamp, ENTITYLESS);
    assertClean(CausationRowRules.uncausedMeansUncaused, ENTITYLESS);
  }

  /**
   * The class-retention proof rides this assertion: {@code OptedOut} satisfies the decision rule
   * only if ArchUnit read the CLASS-retention {@code @Uncaused} from bytecode — the property that
   * lets the real annotation stay invisible at runtime.
   */
  @Test
  void anEntityThatDecidedNothingViolatesTheDecisionRule() {
    assertViolates(
        CausationRowRules.everyEntityDecidesAboutCausation, FORGOTTEN, "ForgotEverything");
  }

  @Test
  void causedRowWithoutTheListenerViolatesTheWiringRule() {
    assertViolates(
        CausationRowRules.everyCausedRowAttachesTheStamp, FORGOTTEN, "ForgotTheListener");
    assertViolates(
        CausationRowRules.everyCausedRowAttachesTheStamp, FORGOTTEN, "ListedTheWrongListener");
  }

  @Test
  void uncausedOnACausedRowViolatesTheContradictionGuard() {
    assertViolates(CausationRowRules.uncausedMeansUncaused, FORGOTTEN, "Contradiction");
  }

  /** The interface without the listener still counts as a decision; only the wiring rule fires. */
  @Test
  void forgettingTheListenerIsNotAlsoAMissingDecision() {
    EvaluationResult result =
        CausationRowRules.everyEntityDecidesAboutCausation.evaluate(FORGOTTEN);
    assertFalse(
        String.join("\n", result.getFailureReport().getDetails()).contains("ForgotTheListener"),
        "implementing CausedRow IS the decision; the wiring rule owns the rest");
  }

  private static void assertClean(ArchRule rule, JavaClasses classes) {
    EvaluationResult result = rule.evaluate(classes);
    assertFalse(
        result.hasViolation(),
        () -> "expected no violations:\n" + String.join("\n", result.getFailureReport().getDetails()));
  }

  private static void assertViolates(ArchRule rule, JavaClasses classes, String offender) {
    EvaluationResult result = rule.evaluate(classes);
    assertTrue(result.hasViolation(), "expected a violation naming " + offender);
    assertTrue(
        String.join("\n", result.getFailureReport().getDetails()).contains(offender),
        () -> offender + " missing from:\n" + String.join("\n", result.getFailureReport().getDetails()));
  }
}
