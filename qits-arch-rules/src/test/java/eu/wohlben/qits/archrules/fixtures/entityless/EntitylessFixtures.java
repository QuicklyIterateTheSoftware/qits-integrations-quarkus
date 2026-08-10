package eu.wohlben.qits.archrules.fixtures.entityless;

/**
 * A package with no entity in it — and, by extension, the stand-in for every package where a rule's
 * match set comes up empty: no {@code @Uncaused} class anywhere (everything participates), or no
 * {@code CausedRow} yet. The self-test pins that all three rules PASS here, because an empty match
 * is the best state a rule describes and must never read as a misconfigured rule. Bought on
 * qits-projects' epics module, where four participating entities and zero opt-outs turned the
 * ideal package into a red build under ArchUnit's fail-on-empty default.
 */
public final class EntitylessFixtures {

  private EntitylessFixtures() {}

  public static class JustAClass {}
}
