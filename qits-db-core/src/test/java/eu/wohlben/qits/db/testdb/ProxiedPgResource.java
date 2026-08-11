package eu.wohlben.qits.db.testdb;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;

/**
 * Stands up the proof rig before Quarkus boots: one embedded postgres, one {@link KillableProxy} in
 * front of it, and a datasource pointed at the proxy rather than at the database.
 *
 * <p><b>Why a lifecycle manager rather than a static field.</b> A {@code @QuarkusTest} runs its test
 * classes in the Quarkus classloader and its configuration is read before that classloader exists,
 * so a plain singleton would be started twice and the test would kill a proxy nobody was talking
 * through. This hook runs once, outside, and hands the one proxy back through {@link
 * TestInjector} — which is the supported way across that boundary.
 *
 * <p>The datasource carries the fleet's own three lines, {@code PatientPgDriver} included, so what
 * the suite exercises is the arrangement the platform actually deploys.
 */
public class ProxiedPgResource implements QuarkusTestResourceLifecycleManager {

  /** The table every test in the rig writes to. One column, because one row count is the assertion. */
  public static final String TABLE = "retry_probe";

  private KillableProxy proxy;

  @Override
  public Map<String, String> start() {
    try {
      int postgres = EmbeddedPg.port();
      createTable();
      proxy = new KillableProxy(postgres);
    } catch (Exception e) {
      throw new IllegalStateException("could not start the proxied postgres rig", e);
    }
    return Map.of(
        "quarkus.datasource.db-kind", "postgresql",
        "quarkus.datasource.username", EmbeddedPg.USER,
        "quarkus.datasource.password", EmbeddedPg.PASSWORD,
        "quarkus.datasource.jdbc.url",
            "jdbc:postgresql://localhost:" + proxy.port() + "/" + EmbeddedPg.DATABASE,
        "quarkus.datasource.jdbc.driver", "eu.wohlben.qits.db.PatientPgDriver",
        "quarkus.datasource.jdbc.validate-on-borrow", "true",
        "quarkus.datasource.jdbc.acquisition-timeout", "15S",
        // A pool that keeps nothing idle: every attempt in these tests must open its own
        // connection, so that "the previous attempt's connection is dead" is a fact about the
        // attempt rather than about the pool's housekeeping.
        "quarkus.datasource.jdbc.min-size", "0",
        "quarkus.datasource.jdbc.initial-size", "0");
  }

  @Override
  public void inject(TestInjector injector) {
    injector.injectIntoFields(proxy, new TestInjector.MatchesType(KillableProxy.class));
  }

  @Override
  public void stop() {
    if (proxy != null) {
      proxy.close();
      proxy = null;
    }
  }

  private static void createTable() throws Exception {
    try (Connection connection =
            DriverManager.getConnection(
                EmbeddedPg.directUrl(), EmbeddedPg.USER, EmbeddedPg.PASSWORD);
        Statement sql = connection.createStatement()) {
      sql.execute("create table if not exists " + TABLE + " (id text primary key)");
    }
  }
}
