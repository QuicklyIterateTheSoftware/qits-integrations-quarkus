package eu.wohlben.qits.db;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * A bounded retry for database work that should hold through a short outage, on <b>connection-class
 * failures only</b>.
 *
 * <p><b>What it exists for.</b> The platform restarts its own postgres. A cutover kills every
 * connection every service is holding, and the pool hands the dead ones out again until something
 * evicts them. Work that is already half done when that happens — bookkeeping after a container is
 * running, a catalogue read a caller is waiting on — has no good answer except to ask again a
 * moment later. Measured twice: a deployment ended {@code FAILED: [JDBCConnectionException …]} while
 * its container was healthy, and a catalogue read answered 404 for a repository that exists.
 *
 * <p><b>Narrow on purpose, in three ways.</b>
 *
 * <ul>
 *   <li><b>Only connection-class failures retry.</b> A constraint violation, a missing row, a bug —
 *       anything that would fail identically on the second attempt — is rethrown at once. Retrying
 *       business logic turns one visible failure into a slow one.
 *   <li><b>The deadline is short</b> ({@link #DEFAULT_DEADLINE}, overridable per call site). It
 *       covers a container restarting, not an outage; a database still gone after that is a failure
 *       worth reporting.
 *   <li><b>It sleeps the calling thread.</b> On a request thread that is a request held open for up
 *       to the deadline, which is the trade being made. Wrap the operations that must survive the
 *       cutover, not every query.
 * </ul>
 *
 * <p><b>The retried block must be re-runnable.</b> A read is. A write that re-reads what it touches
 * and sets it to the same values is. A bare {@code insert} is <b>not</b> — a commit whose outcome
 * the connection died before reporting would be duplicated — so wrap what runs <i>after</i> the
 * irreversible work, where dropping it leaves the world and the rows disagreeing.
 *
 * <p><b>This is the second half of a pair, not the whole answer.</b> The first half is the pool
 * itself: every platform datasource sets {@code validate-on-borrow=true} so dead connections are
 * evicted rather than handed out, and {@code acquisition-timeout=15S} so a starved pool fails a
 * caller instead of hanging it. Without those, this retry spends its deadline receiving the same
 * dead connection.
 */
public final class DbRetry {

  private static final Logger LOG = Logger.getLogger(DbRetry.class);

  /** How long a cutover may last before it is a failure worth reporting. */
  public static final Duration DEFAULT_DEADLINE = Duration.ofSeconds(15);

  /** The wait between attempts. Short: a restarting container comes back in seconds. */
  public static final Duration DEFAULT_PAUSE = Duration.ofMillis(500);

  /**
   * Connection-class exception types this module refuses to depend on, matched by fully-qualified
   * name against the throwable's own class and its superclasses.
   *
   * <p>Hibernate's is here rather than in an {@code instanceof} so that consuming an ORM stays the
   * consumer's decision — see this module's pom. The contract that buys: a rename over there
   * silently un-matches, and the mirror in this module's test sources is where it surfaces.
   */
  private static final Set<String> CONNECTION_TYPES =
      Set.of("org.hibernate.exception.JDBCConnectionException");

  /**
   * SQLState classes that mean "the connection", not "the statement": {@code 08xxx} is the standard
   * connection-exception class, and postgres' {@code 57P0x} is the server telling a client it is
   * shutting down or has terminated its backend — which is a cutover, seen from a caller.
   */
  private static final List<String> CONNECTION_STATES = List.of("08", "57P01", "57P02", "57P03");

  /**
   * The wordings left over when nothing in the chain carries a SQLState — chiefly the pool's own
   * acquisition timeout, which is what a datasource whose server is gone answers with. Matched
   * against {@link SQLException} messages only, never against arbitrary throwables, so a business
   * failure that happens to say "connection" somewhere is not swept in.
   */
  private static final List<String> CONNECTION_MARKERS =
      List.of(
          "acquisition timeout",
          "connection is closed",
          "connection has been closed",
          "connection reset",
          "no connection currently available",
          "i/o error occurred while sending to the backend",
          "terminating connection",
          "the connection attempt failed");

  /** How deep a cause chain is walked. A chain that loops would otherwise hang the caller. */
  private static final int MAX_CAUSE_DEPTH = 30;

  private DbRetry() {}

  /** Runs {@code action}, retrying connection failures until {@link #DEFAULT_DEADLINE}. */
  public static void run(String what, Runnable action) {
    run(what, action, DEFAULT_DEADLINE);
  }

  /** Runs {@code action}, retrying connection failures until {@code deadline}. */
  public static void run(String what, Runnable action, Duration deadline) {
    call(
        what,
        () -> {
          action.run();
          return null;
        },
        deadline,
        DEFAULT_PAUSE);
  }

  /** Calls {@code action}, retrying connection failures until {@link #DEFAULT_DEADLINE}. */
  public static <T> T call(String what, Supplier<T> action) {
    return call(what, action, DEFAULT_DEADLINE);
  }

  /** Calls {@code action}, retrying connection failures until {@code deadline}. */
  public static <T> T call(String what, Supplier<T> action, Duration deadline) {
    return call(what, action, deadline, DEFAULT_PAUSE);
  }

  /**
   * The whole retry. {@code what} names the work in the log — it is read by a person after an
   * outage, so name the operation rather than the method.
   *
   * <p>Every attempt after the deadline has passed is refused, so the last failure is rethrown
   * exactly as it arrived. The action always runs at least once.
   */
  public static <T> T call(String what, Supplier<T> action, Duration deadline, Duration pause) {
    long expiry = System.nanoTime() + deadline.toNanos();
    int attempt = 0;
    while (true) {
      attempt++;
      try {
        T answer = action.get();
        if (attempt > 1) {
          LOG.infof("%s succeeded on attempt %d — the datasource came back", what, attempt);
        }
        return answer;
      } catch (RuntimeException e) {
        if (!isConnectionFailure(e) || System.nanoTime() >= expiry) {
          throw e;
        }
        // The deadline is logged as a Duration, not as seconds: a sub-second one would round to
        // "0s" and read as a bug in the retry rather than as a short deadline.
        LOG.warnf(
            "%s lost its database connection (attempt %d): %s — retrying within %s",
            what, attempt, e.getMessage(), deadline);
      }
      try {
        Thread.sleep(pause.toMillis());
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(what + " was interrupted while waiting for its database");
      }
    }
  }

  /**
   * Whether anything in the cause chain says the connection failed rather than the statement.
   * Public because a caller that catches for itself needs the same answer, and because it is what
   * the tests pin one failure shape at a time.
   */
  public static boolean isConnectionFailure(Throwable thrown) {
    Throwable cause = thrown;
    for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++, cause = cause.getCause()) {
      if (cause instanceof SQLTransientConnectionException
          || cause instanceof SQLNonTransientConnectionException
          || cause instanceof SQLRecoverableException) {
        return true;
      }
      if (isNamedConnectionType(cause.getClass())) {
        return true;
      }
      if (cause instanceof SQLException sql && isConnectionSql(sql)) {
        return true;
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    return false;
  }

  private static boolean isNamedConnectionType(Class<?> type) {
    for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
      if (CONNECTION_TYPES.contains(c.getName())) {
        return true;
      }
    }
    return false;
  }

  private static boolean isConnectionSql(SQLException sql) {
    String state = sql.getSQLState();
    if (state != null && CONNECTION_STATES.stream().anyMatch(state::startsWith)) {
      return true;
    }
    String message = sql.getMessage() == null ? "" : sql.getMessage().toLowerCase(Locale.ROOT);
    return !message.isEmpty() && CONNECTION_MARKERS.stream().anyMatch(message::contains);
  }
}
