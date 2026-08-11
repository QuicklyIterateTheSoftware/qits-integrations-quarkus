package eu.wohlben.qits.archrules;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

/**
 * The rule against configurations a test can write out, the way a service writes them.
 *
 * <p>The config is built from a properties source alone — no default sources — so the host's
 * environment cannot make a green assertion here mean nothing.
 */
class DatasourceBaselineRulesTest {

  private static Config config(String... keysAndValues) {
    Map<String, String> properties = new LinkedHashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      properties.put(keysAndValues[i], keysAndValues[i + 1]);
    }
    return new SmallRyeConfigBuilder()
        .withSources(new PropertiesConfigSource(properties, "the service", 250))
        .build();
  }

  private static String[] baseline(String prefix) {
    return new String[] {
      prefix + "db-kind", "postgresql",
      prefix + "jdbc.driver", DatasourceBaselineRules.PATIENT_DRIVER,
      prefix + "jdbc.validate-on-borrow", "true",
      prefix + "jdbc.acquisition-timeout", "15S",
    };
  }

  @Test
  void aDatasourceCarryingAllThreeLinesPasses() {
    assertDoesNotThrow(
        () ->
            DatasourceBaselineRules.assertBaseline(
                config(baseline("quarkus.datasource.githost."))));
  }

  @Test
  void theDefaultDatasourceIsCheckedToo() {
    // No name in the key at all, which is how a single-datasource service spells it.
    assertDoesNotThrow(
        () -> DatasourceBaselineRules.assertBaseline(config(baseline("quarkus.datasource."))));

    AssertionError caught =
        assertThrows(
            AssertionError.class,
            () ->
                DatasourceBaselineRules.assertBaseline(
                    config("quarkus.datasource.db-kind", "postgresql")));

    assertTrue(caught.getMessage().contains("<default>"), caught.getMessage());
  }

  @Test
  void aQuotedDatasourceNameIsTheSameDatasource() {
    assertDoesNotThrow(
        () ->
            DatasourceBaselineRules.assertBaseline(
                config(baseline("quarkus.datasource.\"platform-deployments\"."))));

    AssertionError caught =
        assertThrows(
            AssertionError.class,
            () ->
                DatasourceBaselineRules.assertBaseline(
                    config("quarkus.datasource.\"platform-deployments\".db-kind", "postgresql")));

    // The quotes are config syntax, not part of the name a person reads.
    assertTrue(caught.getMessage().contains("'platform-deployments'"), caught.getMessage());
  }

  @Test
  void aMissingDriverIsNamedWithTheLineThatFixesIt() {
    AssertionError caught =
        assertThrows(
            AssertionError.class,
            () ->
                DatasourceBaselineRules.assertBaseline(
                    config(
                        "quarkus.datasource.ci.db-kind", "postgresql",
                        "quarkus.datasource.ci.jdbc.validate-on-borrow", "true",
                        "quarkus.datasource.ci.jdbc.acquisition-timeout", "15S")));

    assertTrue(caught.getMessage().contains("datasource 'ci'"), caught.getMessage());
    assertTrue(
        caught
            .getMessage()
            .contains(
                "quarkus.datasource.ci.jdbc.driver=" + DatasourceBaselineRules.PATIENT_DRIVER),
        caught.getMessage());
    assertTrue(caught.getMessage().contains(DatasourceBaselineRules.DOCTRINE), caught.getMessage());
  }

  @Test
  void everyMissingLineIsReportedAtOnce() {
    // Fixing one line per red build teaches nothing the first message could not have said.
    AssertionError caught =
        assertThrows(
            AssertionError.class,
            () ->
                DatasourceBaselineRules.assertBaseline(
                    config("quarkus.datasource.events.db-kind", "postgresql")));

    assertTrue(caught.getMessage().contains("jdbc.driver="), caught.getMessage());
    assertTrue(caught.getMessage().contains("jdbc.validate-on-borrow=true"), caught.getMessage());
    assertTrue(caught.getMessage().contains("jdbc.acquisition-timeout=15S"), caught.getMessage());
  }

  @Test
  void anotherDriverOrAFalseValidationIsAsBadAsAMissingLine() {
    AssertionError caught =
        assertThrows(
            AssertionError.class,
            () ->
                DatasourceBaselineRules.assertBaseline(
                    config(
                        "quarkus.datasource.idp.db-kind", "postgresql",
                        "quarkus.datasource.idp.jdbc.driver", "org.postgresql.Driver",
                        "quarkus.datasource.idp.jdbc.validate-on-borrow", "false",
                        "quarkus.datasource.idp.jdbc.acquisition-timeout", "15S")));

    assertTrue(caught.getMessage().contains("jdbc.driver="), caught.getMessage());
    assertTrue(caught.getMessage().contains("jdbc.validate-on-borrow=true"), caught.getMessage());
  }

  @Test
  void anAcquisitionTimeoutThatIsOnlyQuarkusOwnDefaultDoesNotCount() {
    // The one line of the three whose absence is invisible: Quarkus answers 5S — the very value the
    // baseline replaces — and reports it like any other value, from a source at the bottom ordinal.
    // A service that never wrote the line has to fail, or the rule is checking nothing.
    Map<String, String> defaults = new LinkedHashMap<>();
    defaults.put("quarkus.datasource.ci.jdbc.acquisition-timeout", "5S");
    Config config =
        new SmallRyeConfigBuilder()
            .withSources(
                new PropertiesConfigSource(
                    Map.of(
                        "quarkus.datasource.ci.db-kind", "postgresql",
                        "quarkus.datasource.ci.jdbc.driver", DatasourceBaselineRules.PATIENT_DRIVER,
                        "quarkus.datasource.ci.jdbc.validate-on-borrow", "true"),
                    "the service",
                    250))
            .withSources(new PropertiesConfigSource(defaults, "DefaultValuesConfigSource", Integer.MIN_VALUE))
            .build();

    AssertionError caught =
        assertThrows(AssertionError.class, () -> DatasourceBaselineRules.assertBaseline(config));

    assertTrue(
        caught.getMessage().contains("jdbc.acquisition-timeout=15S"), caught.getMessage());
    // …and the same key, written by the service, passes.
    assertDoesNotThrow(
        () -> DatasourceBaselineRules.assertBaseline(config(baseline("quarkus.datasource.ci."))));
  }

  @Test
  void aDatasourceOfAnotherKindIsNotThisRulesBusiness() {
    // h2 in a test, and whatever the platform runs next, are left alone.
    assertDoesNotThrow(
        () ->
            DatasourceBaselineRules.assertBaseline(
                config("quarkus.datasource.artifacts.db-kind", "h2")));
  }

  @Test
  void severalDatasourcesAreCheckedIndependently() {
    String[] complete = baseline("quarkus.datasource.projects.");
    String[] both = new String[complete.length + 2];
    System.arraycopy(complete, 0, both, 0, complete.length);
    both[complete.length] = "quarkus.datasource.epics.db-kind";
    both[complete.length + 1] = "postgresql";

    AssertionError caught =
        assertThrows(
            AssertionError.class, () -> DatasourceBaselineRules.assertBaseline(config(both)));

    assertTrue(caught.getMessage().contains("datasource 'epics'"), caught.getMessage());
    assertTrue(!caught.getMessage().contains("datasource 'projects'"), caught.getMessage());
  }

  @Test
  void aUrlOverAnUnsetEnvironmentVariableDoesNotDerailTheRule() {
    // How the whole fleet spells its URLs. An unresolvable expression must read as "not this rule's
    // key" rather than fail the build with a message about the wrong line.
    assertDoesNotThrow(
        () ->
            DatasourceBaselineRules.assertBaseline(
                config(
                    "quarkus.datasource.githost.db-kind", "postgresql",
                    "quarkus.datasource.githost.jdbc.url", "${QITS_RESOURCE_DB_URL}",
                    "quarkus.datasource.githost.jdbc.driver", DatasourceBaselineRules.PATIENT_DRIVER,
                    "quarkus.datasource.githost.jdbc.validate-on-borrow", "true",
                    "quarkus.datasource.githost.jdbc.acquisition-timeout", "15S")));
  }

  @Test
  void theNoArgumentFormReadsTheApplicationsOwnConfig() {
    // The form a service calls. This module declares no datasource, so the honest answer is that
    // there is nothing to fail — what is proven here is that the config lookup itself works.
    assertDoesNotThrow(() -> DatasourceBaselineRules.assertBaseline());
  }
}
