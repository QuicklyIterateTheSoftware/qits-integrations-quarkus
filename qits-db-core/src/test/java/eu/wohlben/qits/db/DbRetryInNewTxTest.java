package eu.wohlben.qits.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.db.testdb.KillableProxy;
import eu.wohlben.qits.db.testdb.ProxiedPgResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * {@code inNewTx} against a real PostgreSQL, through a proxy the test kills.
 *
 * <p>Every claim this class makes is a claim about a wire: which failures leave a transaction
 * certainly uncommitted, and which leave it undecidable. A mock of the connection would be written
 * by the same hand as the classification and would prove only that the two agree. So there is a real
 * pool, a real transaction manager, a real server, and a socket in between that dies on command.
 *
 * <p><b>The suite skips under root</b>, because zonky's {@code initdb} refuses to run as the
 * superuser and the platform's CI step containers are Alpine running as one. That is a real hole and
 * it is written down rather than hidden: the wire proof runs on a developer host, and the
 * classifier's own unit tests — {@link DbRetryInNewTxClassificationTest} — run everywhere.
 */
@QuarkusTest
@QuarkusTestResource(ProxiedPgResource.class)
@EnabledIf(
    value = "eu.wohlben.qits.db.testdb.EmbeddedPg#available",
    disabledReason = "zonky's initdb refuses to run as root, which is how the CI step container runs")
class DbRetryInNewTxTest {

  private static final Duration DEADLINE = Duration.ofSeconds(20);
  private static final Duration PAUSE = Duration.ofMillis(50);

  @Inject DataSource dataSource;

  KillableProxy proxy;

  @BeforeEach
  void emptyTheTable() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement sql = connection.createStatement()) {
      sql.execute("delete from " + ProxiedPgResource.TABLE);
    }
  }

  @Test
  void aConnectionLostMidStatementIsRetriedAndTheRowLandsExactlyOnce() {
    AtomicInteger attempts = new AtomicInteger();

    DbRetry.runInNewTx(
        "the probe write",
        () -> {
          int attempt = attempts.incrementAndGet();
          insert("the-one-row");
          if (attempt == 1) {
            // The transaction is open and the insert is on the server. Killing here is the
            // statement phase: nothing has been committed and nothing can have been.
            proxy.killEverything();
            countRows();
          }
        },
        DEADLINE,
        PAUSE);

    assertEquals(2, attempts.get(), "the first attempt should have been retried");
    // The whole point, and the assertion that would fail loudest if the retry doubled a write: the
    // primary key would collide on the second attempt long before this count could read 2.
    assertEquals(1, countRowsOutsideTheBody());
  }

  @Test
  void aConnectionLostBeforeTheFirstStatementIsRetriedToo() {
    AtomicInteger attempts = new AtomicInteger();

    DbRetry.runInNewTx(
        "the probe write with nothing done yet",
        () -> {
          int attempt = attempts.incrementAndGet();
          if (attempt == 1) {
            // Borrow and enlist a live connection, then kill it before it carries a single
            // statement. The server never learns this transaction existed.
            try (Connection borrowed = dataSource.getConnection()) {
              assertFalse(borrowed.isClosed());
            } catch (SQLException e) {
              throw new IllegalStateException(e);
            }
            proxy.killEverything();
          }
          insert("the-one-row");
        },
        DEADLINE,
        PAUSE);

    assertEquals(2, attempts.get());
    assertEquals(1, countRowsOutsideTheBody());
  }

  @Test
  void aFailureThatIsNotTheConnectionIsNotRetriedAndTheRowIsRolledBack() {
    AtomicInteger attempts = new AtomicInteger();

    IllegalStateException caught =
        assertThrows(
            IllegalStateException.class,
            () ->
                DbRetry.runInNewTx(
                    "a write that decides against itself",
                    () -> {
                      attempts.incrementAndGet();
                      insert("never-committed");
                      throw new IllegalStateException("the business said no");
                    },
                    DEADLINE,
                    PAUSE));

    assertEquals("the business said no", caught.getMessage());
    assertEquals(1, attempts.get(), "a business failure fails identically on the second attempt");
    assertEquals(0, countRowsOutsideTheBody(), "the transaction must have rolled back");
  }

  @Test
  void aConnectionLostInsideTheCommitIsRethrownRatherThanRetried() {
    AtomicInteger attempts = new AtomicInteger();
    // The proxy swallows the COMMIT and resets the socket, so the client is left with a commit it
    // can neither confirm nor rule out. That is the one outcome a retry could double.
    proxy.killTheNextCommit();

    RuntimeException caught =
        assertThrows(
            RuntimeException.class,
            () ->
                DbRetry.runInNewTx(
                    "a write whose acknowledgement was lost",
                    () -> {
                      attempts.incrementAndGet();
                      insert("ambiguous");
                    },
                    DEADLINE,
                    PAUSE));

    assertEquals(1, attempts.get(), "an ambiguous commit must never be run again");
    assertTrue(proxy.commitsSeen() > 0, "the kill should have fired on a real COMMIT");
    // What the transaction manager actually says here is the load-bearing measurement, and it is
    // why the taxonomy rethrows the whole commit phase: Narayana reports a commit it could not
    // deliver as a ROLLBACK, with no cause and no mention of a connection. Believing that word
    // would have retried a write that may already be in the database.
    assertTrue(
        caught.getMessage().contains("RollbackException"), "unexpected failure: " + caught);
    assertTrue(caught.getMessage().contains("ARJUNA016053"), "unexpected failure: " + caught);
    assertFalse(
        DbRetry.isConnectionFailure(caught),
        "the connection loss is invisible from here — that is the point");
    assertFalse(DbRetry.retriableInNewTx(caught, false), "expected the classifier to refuse it");
  }

  @Test
  void aCheckedExceptionFromTheBodyReachesTheCallerUnchanged() {
    // The retry is meant to be invisible when it does not fire. Wrapping would hand every caller a
    // new exception type to catch for the privilege of being wrapped.
    AtomicInteger attempts = new AtomicInteger();

    SQLException caught =
        assertThrows(
            SQLException.class,
            () ->
                DbRetry.inNewTx(
                    "a body with a checked failure",
                    () -> {
                      attempts.incrementAndGet();
                      throw new SQLException("no state, no retry", "42601");
                    },
                    DEADLINE,
                    PAUSE));

    assertEquals("no state, no retry", caught.getMessage());
    assertEquals(1, attempts.get());
  }

  @Test
  void aDatabaseThatStaysGoneGivesUpAndReportsTheLastFailure() {
    AtomicInteger attempts = new AtomicInteger();

    assertThrows(
        RuntimeException.class,
        () ->
            DbRetry.runInNewTx(
                "a write into an outage",
                () -> {
                  attempts.incrementAndGet();
                  insert("never-lands");
                  proxy.killEverything();
                  countRows();
                },
                Duration.ofMillis(400),
                PAUSE));

    assertTrue(attempts.get() > 1, "attempts: " + attempts.get());
    assertEquals(0, countRowsOutsideTheBody());
  }

  /** An insert on the transaction's own enlisted connection. */
  private void insert(String id) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement sql =
            connection.prepareStatement("insert into " + ProxiedPgResource.TABLE + " (id) values (?)")) {
      sql.setString(1, id);
      sql.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("the probe insert failed", e);
    }
  }

  /** A statement inside the transaction, used to discover that the connection has died. */
  private void countRows() {
    try (Connection connection = dataSource.getConnection();
        Statement sql = connection.createStatement();
        ResultSet rows = sql.executeQuery("select count(*) from " + ProxiedPgResource.TABLE)) {
      rows.next();
    } catch (SQLException e) {
      throw new IllegalStateException("the probe read failed", e);
    }
  }

  /** The row count as a caller sees it afterwards, on a fresh connection and its own transaction. */
  private int countRowsOutsideTheBody() {
    try (Connection connection = dataSource.getConnection();
        Statement sql = connection.createStatement();
        ResultSet rows = sql.executeQuery("select count(*) from " + ProxiedPgResource.TABLE)) {
      rows.next();
      return rows.getInt(1);
    } catch (SQLException e) {
      throw new IllegalStateException("could not count the probe rows", e);
    }
  }
}
