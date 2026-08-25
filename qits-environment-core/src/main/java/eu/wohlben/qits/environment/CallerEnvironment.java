package eu.wohlben.qits.environment;

/**
 * The ambient <em>caller's</em> tier on this thread: which environment the request being served
 * was sent from, as {@link EnvironmentServerFilter} read it off the {@link EnvironmentHeader#NAME}
 * header. A platform service — which serves every tier and has no tier of its own — reads this to
 * learn which one is calling.
 *
 * <p><b>This is the caller's tier, never this process's own.</b> The process's own tier is plain
 * configuration ({@code qits.environment}, falling back to {@code "platform"}) and needs no
 * thread-local; asking config is always right. What varies per request is who is calling, and
 * {@code null} here means the caller stated no tier — an unstamped client, or a request whose
 * header the edge stripped — which is <em>unknown</em>, deliberately not defaulted to
 * {@code "platform"}: an absence of a claim is not a claim.
 *
 * <p>The discipline is qits-eventstream's {@code CausationScope}, restated for a String: restore
 * the previous value rather than clear, {@code remove()} for null so a pooled worker carries no
 * entry, a plain {@link ThreadLocal} that deliberately does not follow work to another thread. A
 * hand-off to an executor captures {@link #current()} first and re-establishes it with
 * {@link #with}.
 */
public final class CallerEnvironment {

  private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

  private CallerEnvironment() {}

  /** The tier the current request's caller stated, or {@code null} for none. */
  public static String current() {
    return CURRENT.get();
  }

  /**
   * Run {@code body} with {@code environment} as the ambient caller tier, and leave the thread
   * exactly as it was found — whether the body returns or throws. The bridge for work handed to
   * another thread, and the arrangement tests use.
   */
  public static void with(String environment, Runnable body) {
    String previous = CURRENT.get();
    set(environment);
    try {
      body.run();
    } finally {
      set(previous);
    }
  }

  /**
   * Replace the ambient value and hand back what was there — for the paired REST filters, which
   * enter at one method and leave at another and so cannot wrap their region in a {@link Runnable}.
   * Package-private on purpose: everyone who <em>can</em> use {@link #with} must, because a swap
   * whose counterpart never runs is exactly the leak {@code with}'s {@code finally} makes
   * impossible.
   */
  static String swap(String value) {
    String previous = CURRENT.get();
    set(value);
    return previous;
  }

  /** Null is an absence, not a value: {@code remove()} so a pooled thread carries no entry. */
  private static void set(String value) {
    if (value == null) {
      CURRENT.remove();
    } else {
      CURRENT.set(value);
    }
  }
}
