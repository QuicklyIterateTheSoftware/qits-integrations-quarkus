package eu.wohlben.qits.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.Test;

/**
 * The retry in isolation. No datasource and no ORM: what this module owns is the DECISION — retry
 * or not, again or give up — and every input to it is a throwable a test can build.
 *
 * <p>The deadlines here are milliseconds. The shipped one is fifteen seconds and a suite that spent
 * it would be a suite nobody runs.
 */
class DbRetryTest {

  private static final Duration DEADLINE = Duration.ofMillis(300);
  private static final Duration PAUSE = Duration.ofMillis(5);

  /** What Hibernate wraps a dead connection in, as the platform has seen it. */
  private static RuntimeException lostConnection() {
    return new JDBCConnectionException(
        "Unable to acquire JDBC Connection",
        new SQLException("An I/O error occurred while sending to the backend.", "08006"));
  }

  @Test
  void aConnectionThatComesBackIsRetriedRatherThanFailed() {
    AtomicInteger attempts = new AtomicInteger();

    String answer =
        DbRetry.call(
            "the catalogue read",
            () -> {
              if (attempts.incrementAndGet() < 3) {
                throw lostConnection();
              }
              return "found";
            },
            DEADLINE,
            PAUSE);

    assertEquals("found", answer);
    assertEquals(3, attempts.get());
  }

  @Test
  void aBusinessFailureIsNotRetriedAtAll() {
    // The narrowness IS the feature: anything that would fail identically on the second attempt is
    // rethrown at once, so one visible failure never becomes a slow one.
    AtomicInteger attempts = new AtomicInteger();
    IllegalStateException thrown = new IllegalStateException("no active transaction");

    IllegalStateException caught =
        assertThrows(
            IllegalStateException.class,
            () ->
                DbRetry.call(
                    "a write",
                    () -> {
                      attempts.incrementAndGet();
                      throw thrown;
                    },
                    DEADLINE,
                    PAUSE));

    assertSame(thrown, caught);
    assertEquals(1, attempts.get());
  }

  @Test
  void aDatabaseThatStaysGoneRethrowsTheLastFailureWhenTheDeadlineExpires() {
    AtomicInteger attempts = new AtomicInteger();

    JDBCConnectionException caught =
        assertThrows(
            JDBCConnectionException.class,
            () ->
                DbRetry.call(
                    "the cutover bookkeeping",
                    () -> {
                      throw new JDBCConnectionException(
                          "attempt " + attempts.incrementAndGet(),
                          new SQLException("gone", "08006"));
                    },
                    Duration.ofMillis(50),
                    PAUSE));

    // It really did try more than once, it really did stop, and what came out is the LAST failure
    // rather than the first one it remembered.
    assertTrue(attempts.get() > 1, "attempts: " + attempts.get());
    assertEquals("attempt " + attempts.get(), caught.getMessage());
  }

  @Test
  void aRunnableFormExists() {
    AtomicInteger attempts = new AtomicInteger();

    DbRetry.run(
        "the bookkeeping",
        () -> {
          if (attempts.incrementAndGet() < 2) {
            throw lostConnection();
          }
        },
        DEADLINE);

    assertEquals(2, attempts.get());
  }

  @Test
  void theConnectionShapesAPostgresCutoverProduces() {
    // Each of these is a way a cutover reaches a caller, and every one of them was a failed
    // operation on a database that was back seconds later.
    assertTrue(DbRetry.isConnectionFailure(lostConnection()));
    // Hibernate's type matched by NAME alone, with nothing else in the chain to match on. A green
    // assertion here can only mean the name match fired.
    assertTrue(DbRetry.isConnectionFailure(new JDBCConnectionException("Unable to acquire")));
    // The pool, when the server is simply not there: no SQLState at all, which is why the wording
    // list exists beside the state list.
    assertTrue(
        DbRetry.isConnectionFailure(
            new RuntimeException(
                new SQLTransientConnectionException(
                    "Acquisition timeout while waiting for new connection"))));
    // The server saying it is shutting down — postgres' own admin-shutdown state.
    assertTrue(
        DbRetry.isConnectionFailure(
            new RuntimeException(
                new SQLException("terminating connection due to administrator command", "57P01"))));
    // And the standard connection-exception class, whatever the wording.
    assertTrue(DbRetry.isConnectionFailure(new RuntimeException(new SQLException("gone", "08006"))));
  }

  @Test
  void aConstraintViolationIsNotAConnectionFailureThoughBothArriveAsSqlExceptions() {
    // A unique-name collision is 23505 — an integrity violation, not a connection one — and the
    // second attempt would fail exactly as the first did.
    assertFalse(
        DbRetry.isConnectionFailure(
            new RuntimeException(
                new SQLException("duplicate key value violates unique constraint", "23505"))));
    // A plain bug is a bug.
    assertFalse(DbRetry.isConnectionFailure(new IllegalStateException("no active transaction")));
  }

  @Test
  void aCauseChainThatLoopsDoesNotHangTheCaller() {
    // Belt on the walk: this sleeps the calling thread, so a spin here would hang a request.
    SQLException first = new SQLException("odd", "42601");
    SQLException second = new SQLException("odder", "42601");
    first.initCause(second);
    second.initCause(first);

    assertFalse(DbRetry.isConnectionFailure(first));
  }
}
