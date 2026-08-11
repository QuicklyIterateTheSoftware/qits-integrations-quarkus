package eu.wohlben.qits.db;

import io.quarkus.narayana.jta.QuarkusTransaction;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Predicate;
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
 * <p><b>{@link #inNewTx} is that rule made checkable</b>, for the writes it can be. It owns the
 * transaction boundary, so it knows which failures certainly did not commit and retries only those;
 * the one round trip that is genuinely undecidable — the commit acknowledgement — it reports rather
 * than repeats. Its javadoc carries the taxonomy.
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
    return retrying(what, action, deadline, pause, DbRetry::isConnectionFailure);
  }

  /**
   * Runs {@code body} in a <b>new transaction</b>, retrying attempts that certainly did not commit,
   * until {@link #DEFAULT_DEADLINE}.
   *
   * @see #inNewTx(String, Callable, Duration, Duration)
   */
  public static <T> T inNewTx(String what, Callable<T> body) {
    return inNewTx(what, body, DEFAULT_DEADLINE);
  }

  /**
   * Runs {@code body} in a new transaction, retrying attempts that certainly did not commit, until
   * {@code deadline}.
   *
   * @see #inNewTx(String, Callable, Duration, Duration)
   */
  public static <T> T inNewTx(String what, Callable<T> body, Duration deadline) {
    return inNewTx(what, body, deadline, DEFAULT_PAUSE);
  }

  /**
   * Runs {@code body} in a new transaction and retries the whole transaction — a fresh one, on a
   * fresh connection — <b>only when the attempt that failed certainly did not commit</b>.
   *
   * <p><b>Why this exists beside {@link #call}.</b> {@code call} retries a block of work; when that
   * block is a write, a connection that died during the commit round trip would be retried into a
   * second write. This method closes that gap by owning the transaction boundary, which is what
   * makes the outcome knowable. Every attempt is {@code QuarkusTransaction.requiringNew()}, so no
   * attempt ever inherits the previous one's dead connection or its rolled-back state.
   *
   * <h4>The taxonomy</h4>
   *
   * <table border="1">
   *   <caption>What each outcome does</caption>
   *   <tr><th>Outcome</th><th></th></tr>
   *   <tr><td><b>Retry</b></td><td>A connection-class failure thrown <i>out of the body</i> — the
   *       statement phase. Quarkus rolls a failed body back and never commits it, so the position is
   *       known: nothing was written.</td></tr>
   *   <tr><td><b>Rethrow</b></td><td>Everything the transaction manager itself reports — commit,
   *       rollback, heuristic, XA. That is where the ambiguity lives, and none of it can be
   *       placed.</td></tr>
   *   <tr><td><b>Rethrow</b></td><td>Every non-connection failure, exactly as {@link #call} does. A
   *       constraint violation is equally certain not to have committed, and equally certain to fail
   *       the same way on the second attempt.</td></tr>
   * </table>
   *
   * <p><b>Uncertain classifies as rethrow.</b> A caller erroring honestly beats a double-executed
   * write, so a failure this method cannot place is a failure it reports.
   *
   * <h4>The residue, which is by design</h4>
   *
   * <p><b>A failure inside the commit acknowledgement is rethrown.</b> That one round trip is
   * genuinely undecidable from the client: the database may have committed and lost the answer on
   * the way back. Nothing here can make that safe, and this method does not pretend to — it reports
   * it. A caller that needs to survive it needs an idempotent write, not a cleverer retry.
   *
   * <p><b>A rollback the transaction manager claims is not evidence</b>, which is the surprising
   * half. Narayana answers a commit that could not be delivered with {@code RollbackException:
   * ARJUNA016053: Could not commit transaction.} and answers a transaction that really was rolled
   * back before committing the same way — measured on a real wire, 2026-08-11. So the whole
   * commit-phase class is rethrown, and {@link #retriableInNewTx} carries the measurement.
   *
   * <p><b>A flush-phase loss is a commit-phase loss unless you make it otherwise.</b> An ORM flushes
   * at commit by default, which puts the write on the far side of that line. Call {@code
   * entityManager.flush()} (or {@code Panache.flush()}) as the last thing in the body and the write
   * moves into the statement phase, where the classification is certain and a lost connection is
   * retried. It is one line, and it is the difference between this method helping and this method
   * reporting.
   *
   * <p><b>The retry is the whole body.</b> Anything in it that is not a database write — a message
   * sent, a file written, a counter bumped — happens once per attempt.
   *
   * <h4>Idempotent writes do not need any of this</h4>
   *
   * <p>A write that is idempotent by construction — an upsert, an insert on a natural key, a
   * set-to-a-fixed-value update — is safe under plain {@link #call} semantics no matter where the
   * connection died, because a second execution of it is not a second effect. <b>That judgement
   * belongs at the call site</b>, which is the only place that knows the write's shape; this method
   * cannot see it and must not assume it. Use {@code call} where the write is idempotent and this
   * where it is not.
   *
   * <p><b>It needs a running Quarkus application</b> — a transaction manager — which the rest of
   * this class does not. It needs no <i>request</i> context, so a background worker may call it.
   * Call it from outside any open transaction: joining an existing one would make "a fresh
   * transaction per attempt" a lie.
   *
   * <p>A checked exception from {@code body} reaches the caller unchanged, and is never retried
   * unless it carries a connection failure.
   */
  public static <T> T inNewTx(String what, Callable<T> body, Duration deadline, Duration pause) {
    try {
      return retrying(what, () -> oneTransaction(body), deadline, pause, DbRetry::retriableAttempt);
    } catch (BodyFailure marked) {
      throw rethrow(marked.getCause());
    }
  }

  /** Runs {@code body} in a new transaction, until {@link #DEFAULT_DEADLINE}. */
  public static void runInNewTx(String what, Runnable body) {
    runInNewTx(what, body, DEFAULT_DEADLINE);
  }

  /** Runs {@code body} in a new transaction, until {@code deadline}. */
  public static void runInNewTx(String what, Runnable body, Duration deadline) {
    runInNewTx(what, body, deadline, DEFAULT_PAUSE);
  }

  /**
   * The {@link Runnable} shape of {@link #inNewTx(String, Callable, Duration, Duration)}, under its
   * own name rather than as an overload: {@code () -> repository.delete(id)} fits a {@code Runnable}
   * and a {@code Callable} at once, and two same-named methods would make that call site
   * ambiguous rather than convenient.
   */
  public static void runInNewTx(String what, Runnable body, Duration deadline, Duration pause) {
    inNewTx(
        what,
        () -> {
          body.run();
          return null;
        },
        deadline,
        pause);
  }

  /**
   * One attempt: a new transaction around {@code body}, with the body's own failures marked so the
   * classifier can tell them from the transaction manager's.
   *
   * <p><b>The mark is the whole classification.</b> Quarkus rolls a failed body back and never
   * commits it, so "this came out of the body" and "this certainly did not commit" are the same
   * statement. Everything else — begin, commit, the resume afterwards — arrives unmarked, and
   * unmarked is where the ambiguity lives.
   */
  private static <T> T oneTransaction(Callable<T> body) {
    boolean[] fromTheBody = {false};
    try {
      return QuarkusTransaction.requiringNew()
          .call(
              () -> {
                try {
                  return body.call();
                } catch (Throwable failure) {
                  fromTheBody[0] = true;
                  throw new BodyFailure(failure);
                }
              });
    } catch (BodyFailure marked) {
      throw marked;
    } catch (RuntimeException e) {
      // The mark can be lost: a rollback that itself fails is reported by Quarkus in place of the
      // body's exception. The flag survives that, and it says the same thing.
      throw fromTheBody[0] ? new BodyFailure(e) : e;
    }
  }

  /** Whether a failed attempt of {@link #inNewTx} may be run again. */
  private static boolean retriableAttempt(RuntimeException thrown) {
    boolean fromTheBody = thrown instanceof BodyFailure;
    return retriableInNewTx(fromTheBody ? thrown.getCause() : thrown, fromTheBody);
  }

  /**
   * The classification, on its own so a test can pin one outcome at a time and so a caller that
   * catches for itself can ask the same question.
   *
   * <p><b>Two conditions, and both are needed.</b> "The body threw it" is what makes a second
   * attempt <i>safe</i>: Quarkus rolls a failed body back and never commits it, so a body failure is
   * a certain no-commit whatever it was. "It is a connection failure" is what makes a second attempt
   * <i>worth making</i>: a constraint violation is equally certain not to have committed, and
   * equally certain to fail the same way for fifteen seconds.
   *
   * <p><b>Anything the transaction manager itself reports is rethrown, including a rollback it
   * claims.</b> That is not caution, it is a measurement: killing the connection inside the commit
   * round trip (2026-08-11, this module's proof rig) produces {@code
   * QuarkusTransactionException: jakarta.transaction.RollbackException: ARJUNA016053: Could not
   * commit transaction.} — with no cause and no mention of a connection. Narayana spells "the commit
   * failed, outcome unknown" and "the transaction was rolled back before committing" with the same
   * exception type, so a {@code RollbackException} here is not evidence of anything. Reading it as a
   * no-commit would retry exactly the write that may already be in the database.
   *
   * @param thrown what the attempt failed with
   * @param thrownByTheBody whether it came out of the transaction's body rather than out of the
   *     transaction manager
   * @return {@code true} only when the attempt certainly did not commit <b>and</b> failed for a
   *     connection-class reason
   */
  public static boolean retriableInNewTx(Throwable thrown, boolean thrownByTheBody) {
    return thrownByTheBody && isConnectionFailure(thrown);
  }

  /** A body failure, marked so it is never mistaken for a commit failure. Never escapes. */
  private static final class BodyFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    BodyFailure(Throwable cause) {
      super(cause.getMessage(), cause);
    }
  }

  /**
   * Rethrows the body's own exception, checked or not, without wrapping it. Wrapping would hand
   * every caller a new exception type to catch for the privilege of being retried; the retry is
   * meant to be invisible when it does not fire.
   */
  @SuppressWarnings("unchecked")
  private static <E extends Throwable> RuntimeException rethrow(Throwable thrown) throws E {
    throw (E) thrown;
  }

  /**
   * The retry loop itself. {@code retriable} is the whole difference between {@link #call} and
   * {@link #inNewTx}: the first retries a lost connection, the second retries a lost connection
   * whose transaction certainly did not commit.
   */
  private static <T> T retrying(
      String what,
      Supplier<T> action,
      Duration deadline,
      Duration pause,
      Predicate<RuntimeException> retriable) {
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
        if (!retriable.test(e) || System.nanoTime() >= expiry) {
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
    return isNamed(type, CONNECTION_TYPES);
  }

  /** Whether a type or any supertype of it is one of {@code names}, matched by name. */
  private static boolean isNamed(Class<?> type, Set<String> names) {
    for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
      if (names.contains(c.getName())) {
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
