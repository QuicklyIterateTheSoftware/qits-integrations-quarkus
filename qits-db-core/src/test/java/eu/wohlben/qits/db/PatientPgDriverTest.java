package eu.wohlben.qits.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * The driver in isolation, over a fake delegate. No postgres and no pool: what this class owns is
 * the DECISION — retry this failure or not, again or give up — and every input to it is an exception
 * a test can build.
 *
 * <p>The deadlines here are milliseconds. The shipped one is fourteen seconds and a suite that spent
 * it would be a suite nobody runs.
 */
class PatientPgDriverTest {

  private static final String URL = "jdbc:postgresql://db:5432/qits";

  /** SQLState 08006: the standard connection-exception class, which is where "refused" lands. */
  private static SQLException notThere() {
    return new SQLException("Connection to db:5432 refused.", "08006");
  }

  /** What crash recovery answers in the window between accepting TCP and serving. */
  private static SQLException startingUp() {
    return new SQLException("the database system is starting up", "57P03");
  }

  /** A delegate that fails a fixed number of times, then hands out a connection. */
  private static final class FakeDelegate implements Driver {
    private final List<SQLException> failures = new ArrayList<>();
    private final Connection connection;
    private final List<Properties> seen = new ArrayList<>();
    private int calls;

    FakeDelegate(Connection connection, SQLException... failures) {
      this.connection = connection;
      this.failures.addAll(List.of(failures));
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
      seen.add(info);
      if (calls++ < failures.size()) {
        throw failures.get(calls - 1);
      }
      if (connection == null) {
        throw failures.isEmpty() ? notThere() : failures.get(failures.size() - 1);
      }
      return connection;
    }

    @Override
    public boolean acceptsURL(String url) {
      return true;
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
      seen.add(info);
      return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
      return 42;
    }

    @Override
    public int getMinorVersion() {
      return 7;
    }

    @Override
    public boolean jdbcCompliant() {
      return true;
    }

    @Override
    public java.util.logging.Logger getParentLogger() {
      return java.util.logging.Logger.getGlobal();
    }
  }

  /** A delegate that never fails and always fails the same way is enough for every case here. */
  private static Connection stubConnection() {
    return (Connection)
        java.lang.reflect.Proxy.newProxyInstance(
            PatientPgDriverTest.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> null);
  }

  private static Properties patience(long deadlineMs, long pauseMs) {
    Properties info = new Properties();
    info.setProperty(PatientPgDriver.DEADLINE_PROPERTY, Long.toString(deadlineMs));
    info.setProperty(PatientPgDriver.PAUSE_PROPERTY, Long.toString(pauseMs));
    return info;
  }

  @Test
  void aDatabaseThatComesBackIsWaitedForRatherThanFailed() throws SQLException {
    Connection opened = stubConnection();
    FakeDelegate delegate = new FakeDelegate(opened, notThere(), notThere());
    PatientPgDriver driver = new PatientPgDriver(delegate);

    Connection answer = driver.connect(URL, patience(2_000, 5));

    assertSame(opened, answer);
    assertEquals(3, delegate.calls);
  }

  @Test
  void theStartingUpWindowIsWaitedForToo() throws SQLException {
    // Crash recovery accepts TCP about 1.3s before it serves, and answers 57P03 in between.
    // Retrying "refused" only would give up exactly one phase early.
    Connection opened = stubConnection();
    FakeDelegate delegate = new FakeDelegate(opened, notThere(), startingUp(), startingUp());
    PatientPgDriver driver = new PatientPgDriver(delegate);

    assertSame(opened, driver.connect(URL, patience(2_000, 5)));
    assertEquals(4, delegate.calls);
  }

  @Test
  void aWrongPasswordFailsOnTheFirstAttempt() {
    // The narrowness IS the feature. 28P01 would fail identically for fourteen seconds, and a
    // service whose credentials are wrong should say so at once.
    SQLException refused =
        new SQLException("password authentication failed for user \"qits\"", "28P01");
    FakeDelegate delegate = new FakeDelegate(stubConnection(), refused);
    PatientPgDriver driver = new PatientPgDriver(delegate);

    SQLException caught =
        assertThrows(SQLException.class, () -> driver.connect(URL, patience(2_000, 5)));

    assertSame(refused, caught);
    assertEquals(1, delegate.calls);
  }

  @Test
  void aNonRetriableFailureMidWaitEndsTheWaitAtOnce() {
    SQLException fatal = new SQLException("database \"qits\" does not exist", "3D000");
    FakeDelegate delegate = new FakeDelegate(stubConnection(), notThere(), fatal);
    PatientPgDriver driver = new PatientPgDriver(delegate);

    assertSame(
        fatal, assertThrows(SQLException.class, () -> driver.connect(URL, patience(2_000, 5))));
    assertEquals(2, delegate.calls);
  }

  @Test
  void aDatabaseThatStaysGoneRethrowsTheLastFailure() {
    SQLException first = notThere();
    SQLException second = new SQLException("still refused", "08006");
    SQLException third = new SQLException("refused, the last word", "08006");
    FakeDelegate delegate = new FakeDelegate(null, first, second, third);
    PatientPgDriver driver = new PatientPgDriver(delegate);

    SQLException caught =
        assertThrows(SQLException.class, () -> driver.connect(URL, patience(60, 5)));

    // What comes out is the state of the database NOW, not the one patience started with.
    assertSame(third, caught);
    assertTrue(delegate.calls > 1, "calls: " + delegate.calls);
  }

  @Test
  void aDeadlineOfZeroIsOneAttempt() {
    SQLException only = notThere();
    FakeDelegate delegate = new FakeDelegate(null, only);
    PatientPgDriver driver = new PatientPgDriver(delegate);

    assertSame(only, assertThrows(SQLException.class, () -> driver.connect(URL, patience(0, 5))));
    assertEquals(1, delegate.calls);
  }

  @Test
  void anInterruptGivesUpAndLeavesTheFlagSet() {
    FakeDelegate delegate = new FakeDelegate(null, notThere());
    PatientPgDriver driver = new PatientPgDriver(delegate);

    Thread.currentThread().interrupt();
    try {
      SQLException caught =
          assertThrows(SQLException.class, () -> driver.connect(URL, patience(2_000, 5)));

      assertEquals("08006", caught.getSQLState());
      // Whoever interrupted this thread is shutting something down; swallowing the flag would hide
      // that from every layer above.
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void aUrlThisDriverDoesNotOwnAnswersNullAndNeverReachesTheDelegate() throws SQLException {
    FakeDelegate delegate = new FakeDelegate(stubConnection());
    PatientPgDriver driver = new PatientPgDriver(delegate);

    assertFalse(driver.acceptsURL("jdbc:h2:mem:test"));
    assertNull(driver.connect("jdbc:h2:mem:test", new Properties()));
    assertNull(driver.connect(null, new Properties()));
    assertEquals(0, delegate.calls);
    // Plain postgres URLs, no prefix of our own: Agroal is handed the driver class explicitly.
    assertTrue(driver.acceptsURL(URL));
  }

  @Test
  void theKnobsAreReadAndKeptAwayFromPostgres() throws SQLException {
    Properties info = patience(2_000, 5);
    info.setProperty("ApplicationName", "qits-githost");
    FakeDelegate delegate = new FakeDelegate(stubConnection(), notThere());
    PatientPgDriver driver = new PatientPgDriver(delegate);

    driver.connect(URL, info);

    // The overrides worked: a 14s default would not have retried inside this test.
    assertEquals(2, delegate.calls);
    for (Properties passed : delegate.seen) {
      assertNull(passed.getProperty(PatientPgDriver.DEADLINE_PROPERTY));
      assertNull(passed.getProperty(PatientPgDriver.PAUSE_PROPERTY));
      assertEquals("qits-githost", passed.getProperty("ApplicationName"));
    }
    // The caller's own Properties are untouched — Agroal reuses that object for every creation.
    assertEquals("2000", info.getProperty(PatientPgDriver.DEADLINE_PROPERTY));
  }

  @Test
  void anUnreadableKnobFallsBackRatherThanFailingTheConnection() throws SQLException {
    Properties info = new Properties();
    info.setProperty(PatientPgDriver.DEADLINE_PROPERTY, "fourteen seconds");
    Connection opened = stubConnection();
    PatientPgDriver driver = new PatientPgDriver(new FakeDelegate(opened));

    // A typo in a config line must not take a service down while the database is healthy.
    assertSame(opened, driver.connect(URL, info));
  }

  @Test
  void theRetryPolicyIsTwoStatesAndNoMore() {
    assertTrue(PatientPgDriver.retriable(notThere()));
    assertTrue(PatientPgDriver.retriable(new SQLException("connect timeout", "08001")));
    assertTrue(PatientPgDriver.retriable(startingUp()));
    // Shutting down (57P01) is NOT retried at creation: a server on its way out refuses the next
    // attempt too, and the caller is better told now.
    assertFalse(PatientPgDriver.retriable(new SQLException("terminating connection", "57P01")));
    assertFalse(PatientPgDriver.retriable(new SQLException("syntax error", "42601")));
    assertFalse(PatientPgDriver.retriable(new SQLException("no state at all")));
  }

  @Test
  void theDelegateAnswersEverythingThatIsNotPatience() throws SQLException {
    FakeDelegate delegate = new FakeDelegate(stubConnection());
    PatientPgDriver driver = new PatientPgDriver(delegate);

    assertEquals(42, driver.getMajorVersion());
    assertEquals(7, driver.getMinorVersion());
    assertTrue(driver.jdbcCompliant());
    assertEquals(0, driver.getPropertyInfo(URL, patience(1, 1)).length);
    assertNull(delegate.seen.get(0).getProperty(PatientPgDriver.DEADLINE_PROPERTY));
  }

  @Test
  void theRealDriverIsWhatTheNoArgConstructorWraps() throws SQLException {
    // The constructor Agroal calls, and the proof that pgjdbc is on the classpath the config names.
    PatientPgDriver driver = new PatientPgDriver();

    assertTrue(driver.acceptsURL(URL));
    assertNull(driver.connect("jdbc:h2:mem:test", new Properties()));
    assertTrue(driver.getMajorVersion() > 0);
  }
}
