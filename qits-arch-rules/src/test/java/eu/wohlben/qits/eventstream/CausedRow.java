package eu.wohlben.qits.eventstream;

/**
 * A TEST FIXTURE, not the real type. {@code CausationRowRules} matches qits-eventstream's types by
 * fully-qualified name — the pom says why there is no dependency to import the real ones — so the
 * fixtures the self-test compiles must carry those exact names, which also forces them to be
 * top-level ({@code Mirror$CausedRow} would match nothing). Three files mirror the three names the
 * rules judge: this interface, {@link CausationStamp} and {@link Uncaused}. If a name over there
 * changes, the rules' constants, this mirror and every consumer's build change together, and this
 * suite is where the drift surfaces first.
 */
public interface CausedRow {}
