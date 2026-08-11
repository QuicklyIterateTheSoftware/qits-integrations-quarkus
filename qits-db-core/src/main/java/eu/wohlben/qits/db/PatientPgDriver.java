package eu.wohlben.qits.db;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import org.jboss.logging.Logger;

/**
 * A JDBC driver that waits for postgres to come back, wrapped around the real one.
 *
 * <p><b>Patience belongs at connection creation.</b> Retrying an operation can execute a write
 * twice, because a commit whose acknowledgement was lost still happened. Opening a connection has
 * executed nothing, so holding and retrying <i>there</i> is safe for reads and for writes alike.
 * That is what makes this universal where {@link DbRetry} has to be placed by hand.
 *
 * <p><b>Held means held.</b> The caller's thread blocks inside {@code connect}. Nothing is
 * acknowledged early, nothing is buffered, nothing is applied later. A request that outlives the
 * deadline gets the real failure, and nothing has happened anywhere.
 *
 * <p><b>Adoption is one line per datasource</b>, beside the two pool lines it composes with:
 *
 * <pre>{@code
 * quarkus.datasource.<name>.jdbc.driver=eu.wohlben.qits.db.PatientPgDriver
 * quarkus.datasource.<name>.jdbc.validate-on-borrow=true
 * quarkus.datasource.<name>.jdbc.acquisition-timeout=15S
 * }</pre>
 *
 * <p>{@code validate-on-borrow} turns a dead pooled connection into a fresh creation attempt, which
 * is what this driver makes patient; {@code acquisition-timeout} keeps the Agroal waiter alive while
 * it works. The default deadline is deliberately under that timeout, so a caller sees the database's
 * own refusal rather than a generic acquisition timeout.
 *
 * <p><b>What is retried, and nothing more:</b> SQLState {@code 08*} (the standard
 * connection-exception class, which is where "connection refused" lands) and {@code 57P03}, postgres
 * answering "the database system is starting up". Crash recovery accepts TCP about 1.3s before it
 * serves, measured on 2026-08-11, so refused-only patience would give up exactly one phase early.
 * Everything else — a wrong password, a syntax error, a missing database — is rethrown on the first
 * attempt.
 *
 * <p><b>No bound of its own, on purpose.</b> Agroal serializes all connection creation on one
 * executor thread per pool (measured: peak in flight 1, with ten concurrent callers), so at most one
 * patient loop runs per datasource and every other caller waits in the acquisition queue under its
 * own timeout. A semaphore here would bound something that is already single flight.
 *
 * <p>It accepts plain {@code jdbc:postgresql:} URLs: Agroal is handed the driver class explicitly,
 * so there is no {@link java.sql.DriverManager} ambiguity to disambiguate with a URL prefix, and the
 * injected {@code QITS_RESOURCE_*_URL} contract is untouched. For the same reason this class
 * registers itself with {@code DriverManager} nowhere — instantiating it is Agroal's job, and a
 * global registration could shadow pgjdbc for a caller that never asked for patience.
 */
public class PatientPgDriver implements Driver {

  private static final Logger LOG = Logger.getLogger(PatientPgDriver.class);

  /** The only URLs this driver answers to. Everything else is another driver's business. */
  static final String POSTGRES_PREFIX = "jdbc:postgresql:";

  /** JDBC property overriding {@link #DEFAULT_DEADLINE_MS}. */
  public static final String DEADLINE_PROPERTY = "qitsPatienceDeadlineMs";

  /** JDBC property overriding {@link #DEFAULT_PAUSE_MS}. */
  public static final String PAUSE_PROPERTY = "qitsPatiencePauseMs";

  /** Under the fleet's 15S acquisition-timeout, so the caller gets the real refusal. */
  public static final long DEFAULT_DEADLINE_MS = 14_000L;

  /** The wait between attempts. Short: a restarting postgres comes back in seconds. */
  public static final long DEFAULT_PAUSE_MS = 250L;

  private final Driver delegate;

  /** The constructor Agroal calls: patience over the real pgjdbc driver. */
  public PatientPgDriver() {
    this(new org.postgresql.Driver());
  }

  /** For the tests, which pin the DECISION — retry or not, again or give up — with a fake delegate. */
  PatientPgDriver(Driver delegate) {
    this.delegate = delegate;
  }

  /**
   * Opens a connection, retrying a database that is not there yet until the deadline.
   *
   * <p>Returns {@code null} for a URL this driver does not accept, as the JDBC contract requires.
   * The last failure is what comes out when the deadline expires — the caller reads the state of the
   * database now, not when patience began.
   */
  @Override
  public Connection connect(String url, Properties info) throws SQLException {
    if (!acceptsURL(url)) {
      return null;
    }
    Properties delegated = withoutPatienceProperties(info);
    long deadlineMs = millis(info, DEADLINE_PROPERTY, DEFAULT_DEADLINE_MS);
    long pauseMs = millis(info, PAUSE_PROPERTY, DEFAULT_PAUSE_MS);
    long expiry = System.nanoTime() + deadlineMs * 1_000_000L;

    SQLException last;
    try {
      return delegate.connect(url, delegated);
    } catch (SQLException first) {
      if (!retriable(first)) {
        throw first;
      }
      last = first;
    }

    int attempt = 1;
    while (System.nanoTime() < expiry) {
      LOG.warnf(
          "The database is not there (attempt %d, SQLState %s): %s — holding the connection request"
              + " for up to %dms",
          attempt, last.getSQLState(), last.getMessage(), deadlineMs);
      try {
        Thread.sleep(pauseMs);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw last;
      }
      attempt++;
      try {
        Connection connection = delegate.connect(url, delegated);
        LOG.infof("The database answered on attempt %d — the connection request held", attempt);
        return connection;
      } catch (SQLException again) {
        if (!retriable(again)) {
          throw again;
        }
        last = again;
      }
    }
    throw last;
  }

  /**
   * Whether a failure means "the database is not there yet" rather than "the database said no".
   *
   * <p>Public because it is the whole policy in one place, and a caller reasoning about its own
   * failures deserves the same answer.
   */
  public static boolean retriable(SQLException failure) {
    String state = failure.getSQLState();
    return state != null && (state.startsWith("08") || state.equals("57P03"));
  }

  /** {@code jdbc:postgresql:} and nothing else — pgjdbc's own URLs, patience added. */
  @Override
  public boolean acceptsURL(String url) {
    return url != null && url.startsWith(POSTGRES_PREFIX);
  }

  @Override
  public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
    return delegate.getPropertyInfo(url, withoutPatienceProperties(info));
  }

  @Override
  public int getMajorVersion() {
    return delegate.getMajorVersion();
  }

  @Override
  public int getMinorVersion() {
    return delegate.getMinorVersion();
  }

  /** The delegate's answer: this class changes when a connection opens, not what it is. */
  @Override
  public boolean jdbcCompliant() {
    return delegate.jdbcCompliant();
  }

  @Override
  public java.util.logging.Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException {
    return delegate.getParentLogger();
  }

  /**
   * A copy of the connection properties with this driver's own knobs removed. pgjdbc rejects nothing
   * it does not know, but it logs unknown properties, and a property that means something here must
   * not read as a postgres setting downstream.
   */
  private static Properties withoutPatienceProperties(Properties info) {
    Properties copy = new Properties();
    if (info == null) {
      return copy;
    }
    for (String name : info.stringPropertyNames()) {
      if (!DEADLINE_PROPERTY.equals(name) && !PAUSE_PROPERTY.equals(name)) {
        copy.setProperty(name, info.getProperty(name));
      }
    }
    return copy;
  }

  /**
   * A millisecond knob from the connection properties. An unreadable value falls back to the default
   * rather than failing the connection: a typo in a config line must not take a service down when
   * the database is healthy.
   */
  private static long millis(Properties info, String name, long fallback) {
    String raw = info == null ? null : info.getProperty(name);
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    try {
      long parsed = Long.parseLong(raw.trim());
      return parsed < 0 ? fallback : parsed;
    } catch (NumberFormatException notANumber) {
      LOG.warnf("%s is not a number (%s) — using %dms", name, raw, fallback);
      return fallback;
    }
  }
}
