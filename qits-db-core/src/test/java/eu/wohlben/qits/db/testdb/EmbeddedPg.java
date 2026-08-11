package eu.wohlben.qits.db.testdb;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * One real PostgreSQL for this module's surefire JVM.
 *
 * <p><b>Why a real one.</b> What {@code DbRetry.inNewTx} claims is a claim about outcomes on a wire:
 * which failures leave a transaction certainly uncommitted and which leave it undecidable. A mock
 * cannot produce either, because a mock is written by the same person as the claim. Zonky resolves
 * real postgres binaries as ordinary Maven artifacts and this class spawns them as a child process —
 * a dependency, not a daemon — which is what keeps qits-eventstream's rule true here too: a clone of
 * this repo alone builds and tests green, with no docker and no credentials.
 *
 * <p><b>It refuses to run as root, and so does this suite.</b> {@code initdb} exits rather than run
 * as the superuser, and the platform's own CI step containers are Alpine running as root. {@link
 * #available()} is what the postgres-backed test classes gate on, so that a build in a root
 * container skips them loudly instead of failing — see {@code DbRetryInNewTxTest}.
 *
 * <p>The instance is tracked in a system property as well as a static field: a Quarkus test run
 * loads config sources in more than one classloader, and the property is the one thing those copies
 * share.
 */
public final class EmbeddedPg {

  /** Zonky's superuser. Its authentication is `trust`, so the password below is a placeholder. */
  public static final String USER = "postgres";

  /** Any string does: the embedded instance trusts local connections. Never a real credential. */
  public static final String PASSWORD = "embedded";

  /** Named for this repository, so a sibling suite on the same host cannot mean this database. */
  public static final String DATABASE = "qits_db_core_test";

  private static final String PORT_PROPERTY = "qits.test.embedded-pg.port";

  private static EmbeddedPostgres started;

  private EmbeddedPg() {}

  /**
   * Whether an embedded postgres can be started here at all. False under root, which is how the
   * platform's Alpine CI step containers run and where {@code initdb} refuses to work.
   */
  public static boolean available() {
    return !"root".equals(System.getProperty("user.name"));
  }

  /** The port the one embedded instance listens on, starting it on the first call. */
  public static synchronized int port() {
    String recorded = System.getProperty(PORT_PROPERTY);
    if (recorded != null) {
      return Integer.parseInt(recorded);
    }
    try {
      started = EmbeddedPostgres.builder().start();
    } catch (Exception e) {
      throw new IllegalStateException("could not start the embedded postgres", e);
    }
    System.setProperty(PORT_PROPERTY, String.valueOf(started.getPort()));
    Runtime.getRuntime().addShutdownHook(new Thread(EmbeddedPg::stop, "embedded-pg-stop"));
    return started.getPort();
  }

  /** A JDBC url for the test database, going direct rather than through the proxy. */
  public static synchronized String directUrl() {
    ensureDatabase();
    return "jdbc:postgresql://localhost:" + port() + "/" + DATABASE;
  }

  private static void ensureDatabase() {
    String admin = "jdbc:postgresql://localhost:" + port() + "/postgres";
    try (Connection connection = DriverManager.getConnection(admin, USER, PASSWORD);
        Statement sql = connection.createStatement()) {
      try (ResultSet found =
          sql.executeQuery("select 1 from pg_database where datname = '" + DATABASE + "'")) {
        if (found.next()) {
          return;
        }
      }
      sql.execute("create database " + DATABASE);
    } catch (Exception e) {
      throw new IllegalStateException("could not create the test database " + DATABASE, e);
    }
  }

  private static synchronized void stop() {
    if (started != null) {
      try {
        started.close();
      } catch (Exception e) {
        // A JVM on its way out; a postgres that outlives it by a moment is not worth a stack trace.
      }
      started = null;
    }
  }
}
