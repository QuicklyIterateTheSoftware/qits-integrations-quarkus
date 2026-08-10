package eu.wohlben.qits.archrules.fixtures.forgotten;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import eu.wohlben.qits.eventstream.Uncaused;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import java.util.UUID;

/** One fixture per way of getting it wrong; the self-test pins each to the rule that catches it. */
public final class ForgottenFixtures {

  private ForgottenFixtures() {}

  /** Decided nothing: the hole in the trace the decision rule exists for. */
  @Entity
  public static class ForgotEverything {}

  /** Looks like it participates, stamps nothing: the interface without the listener. */
  @Entity
  public static class ForgotTheListener implements CausedRow {
    public UUID causationId;
  }

  /** A listener list that does not name the stamp is the same forgotten wiring. */
  @Entity
  @EntityListeners(WrongListener.class)
  public static class ListedTheWrongListener implements CausedRow {
    public UUID causationId;
  }

  /** Participates and denies it at once. */
  @Entity
  @Uncaused
  @EntityListeners(CausationStamp.class)
  public static class Contradiction implements CausedRow {
    public UUID causationId;
  }

  public static class WrongListener {}
}
