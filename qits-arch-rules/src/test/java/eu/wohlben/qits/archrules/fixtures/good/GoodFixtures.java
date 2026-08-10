package eu.wohlben.qits.archrules.fixtures.good;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import eu.wohlben.qits.eventstream.Uncaused;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import java.util.UUID;

/** A package where every entity decided: the shape the rules must pass without a word. */
public final class GoodFixtures {

  private GoodFixtures() {}

  /** The full participation, exactly as the CausedRow javadoc prescribes. */
  @Entity
  @EntityListeners(CausationStamp.class)
  public static class Participating implements CausedRow {
    public UUID causationId;
  }

  /** The listener list may hold more than the stamp; naming it anywhere suffices. */
  @Entity
  @EntityListeners({OtherListener.class, CausationStamp.class})
  public static class ParticipatingAmongOthers implements CausedRow {
    public UUID causationId;
  }

  /** The written opt-out. */
  @Entity
  @Uncaused
  public static class OptedOut {}

  /** Not an entity: the rules have nothing to say about it, participating or not. */
  public static class PlainClass {}

  public static class OtherListener {}
}
