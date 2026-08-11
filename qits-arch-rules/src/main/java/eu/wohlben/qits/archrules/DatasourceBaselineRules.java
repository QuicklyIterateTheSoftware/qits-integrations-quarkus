package eu.wohlben.qits.archrules;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.ConfigValue;

/**
 * The datasource resilience baseline, as a build failure.
 *
 * <p>The platform restarts its own postgres, and a service survives that cutover only if its pool is
 * configured for it. Three lines per postgresql datasource, and all three are needed:
 *
 * <pre>{@code
 * quarkus.datasource.<name>.jdbc.driver=eu.wohlben.qits.db.PatientPgDriver
 * quarkus.datasource.<name>.jdbc.validate-on-borrow=true
 * quarkus.datasource.<name>.jdbc.acquisition-timeout=15S
 * }</pre>
 *
 * <p>The driver holds a connection request while postgres is coming back; {@code validate-on-borrow}
 * turns a dead pooled connection into a fresh creation attempt, which is what the driver makes
 * patient; {@code acquisition-timeout} keeps the pool's waiter alive while it works. Miss one and
 * the other two do less than they read as. The doctrine and the measurements live in the
 * superproject's {@code docs/project-setup-quinoa-angular.md}.
 *
 * <p><b>{@code acquisition-timeout} must be written down, not merely answerable.</b> Quarkus
 * defaults it to {@code 5S} and reports that default like any other value, so a service that never
 * wrote the line used to pass this rule — the one line of the three whose absence is invisible. It
 * is now checked as a declaration: see {@code declared} for the measurement.
 *
 * <p><b>Why a config rule sits with the ArchUnit rules.</b> Both answer the same question — is this
 * service built the way the platform is built — and both are enabled the same way: one test-scope
 * dependency and a short test class.
 *
 * <pre>{@code
 * class DatasourceBaselineTest {
 *   @Test
 *   void everyPostgresDatasourceCarriesTheBaseline() {
 *     DatasourceBaselineRules.assertBaseline();
 *   }
 * }
 * }</pre>
 *
 * <p>The driver is named as a <b>string</b>, the module's standing trick: this module depends on
 * neither qits-db-core nor a datasource, config carries names, and a bare clone builds with no
 * platform registry. The price is that renaming the driver must update the constant here.
 */
public final class DatasourceBaselineRules {

  /** The driver every postgresql datasource on the platform opens its connections through. */
  public static final String PATIENT_DRIVER = "eu.wohlben.qits.db.PatientPgDriver";

  /** Where the doctrine and the measurements behind it are written down. */
  public static final String DOCTRINE = "docs/project-setup-quinoa-angular.md";

  private static final String ROOT = "quarkus.datasource.";
  private static final String KIND = "db-kind";
  private static final String POSTGRESQL = "postgresql";

  /** How the default datasource reads in a failure message; it has no name of its own. */
  private static final String DEFAULT_DATASOURCE = "<default>";

  private DatasourceBaselineRules() {}

  /** Asserts the baseline over the application's own configuration. */
  public static void assertBaseline() {
    assertBaseline(ConfigProvider.getConfig());
  }

  /**
   * Asserts the baseline over a given configuration. Every postgresql datasource the config declares
   * is checked, and <b>every</b> missing line is reported at once — a service fixing one line at a
   * time per build learns nothing the first message could not have told it.
   *
   * <p>A datasource of another kind is not this rule's business: h2 in a test, and whatever the
   * platform runs next, are left alone.
   *
   * @throws AssertionError naming each datasource and the exact line it is missing
   */
  public static void assertBaseline(Config config) {
    List<String> missing = new ArrayList<>();
    for (var datasource : postgresqlDatasources(config).entrySet()) {
      String name = datasource.getKey();
      String prefix = datasource.getValue();

      String driver = prefix + "jdbc.driver";
      if (!PATIENT_DRIVER.equals(value(config, driver).orElse(null))) {
        missing.add(line(name, driver + "=" + PATIENT_DRIVER, "a cutover fails every open request"));
      }

      String validate = prefix + "jdbc.validate-on-borrow";
      if (!value(config, validate).map(String::trim).map(Boolean::parseBoolean).orElse(false)) {
        missing.add(
            line(name, validate + "=true", "the pool hands out dead connections after the cutover"));
      }

      String acquisition = prefix + "jdbc.acquisition-timeout";
      if (!declared(config, acquisition)) {
        missing.add(
            line(
                name,
                acquisition + "=15S",
                "the default 5S gives up before the successor takes the alias"));
      }
    }
    if (!missing.isEmpty()) {
      throw new AssertionError(
          "The datasource resilience baseline is incomplete. Add these lines, then see "
              + DOCTRINE
              + " for why each one is there:\n"
              + String.join("\n", missing));
    }
  }

  /**
   * The postgresql datasources this config declares, as name to key prefix, found by scanning for
   * {@code db-kind} rather than by asking for a list — MicroProfile config has no list of
   * datasources, and the fleet declares them in properties files under several shapes: the default
   * datasource ({@code quarkus.datasource.db-kind}), a named one ({@code
   * quarkus.datasource.<name>.db-kind}) and a quoted name.
   */
  private static TreeMap<String, String> postgresqlDatasources(Config config) {
    TreeMap<String, String> found = new TreeMap<>();
    for (String property : config.getPropertyNames()) {
      if (!property.startsWith(ROOT) || !property.endsWith(KIND)) {
        continue;
      }
      String middle = property.substring(ROOT.length(), property.length() - KIND.length());
      String name;
      if (middle.isEmpty()) {
        name = DEFAULT_DATASOURCE;
      } else if (middle.endsWith(".")) {
        name = unquoted(middle.substring(0, middle.length() - 1));
      } else {
        continue; // Something else that merely ends in "db-kind".
      }
      if (value(config, property).filter(POSTGRESQL::equalsIgnoreCase).isPresent()) {
        found.put(name, ROOT + middle);
      }
    }
    return found;
  }

  private static String unquoted(String name) {
    return name.length() > 1 && name.startsWith("\"") && name.endsWith("\"")
        ? name.substring(1, name.length() - 1)
        : name;
  }

  /**
   * Whether a property is <b>written down</b>, as opposed to merely answerable.
   *
   * <p>This exists because one line of the baseline has a Quarkus default and the other two do not.
   * {@code jdbc.driver} defaults to nothing and {@code validate-on-borrow} defaults to the wrong
   * value, so reading them is enough. {@code acquisition-timeout} defaults to {@code 5S} — the very
   * value the baseline is there to replace — so a service that never wrote the line reads as
   * complete. Measured on a live Quarkus 3.34.6 configuration, 2026-08-11: an unset {@code
   * jdbc.max-size} answers {@code 50} and <b>appears among the property names</b>, from a source
   * called {@code DefaultValuesConfigSource} at ordinal {@code Integer.MIN_VALUE}.
   *
   * <p>So the name alone does not decide it and the value alone does not either. What separates a
   * declaration from a default is where it came from: a name that is present, from any source a
   * person could have written.
   *
   * <p>An unresolvable expression counts as declared. The line is there; what it evaluates to in a
   * test JVM with no environment is not this rule's business.
   */
  private static boolean declared(Config config, String property) {
    boolean named = false;
    for (String name : config.getPropertyNames()) {
      // A profiled spelling is the same declaration: `%prod.` in front of the key, and the key
      // itself is what the fleet writes.
      if (name.equals(property) || (name.startsWith("%") && name.endsWith("." + property))) {
        named = true;
        break;
      }
    }
    if (!named) {
      return false;
    }
    try {
      ConfigValue found = config.getConfigValue(property);
      if (found == null || found.getValue() == null || found.getValue().isBlank()) {
        return false;
      }
      return found.getSourceOrdinal() > Integer.MIN_VALUE;
    } catch (RuntimeException unresolvable) {
      return true;
    }
  }

  /**
   * One property, or nothing. A value that cannot be produced — an expression over an unset
   * environment variable, which is how the fleet spells its URLs and passwords — reads as absent
   * rather than failing the whole rule with a message about the wrong key.
   */
  private static Optional<String> value(Config config, String property) {
    try {
      return config.getOptionalValue(property, String.class);
    } catch (RuntimeException unresolvable) {
      return Optional.empty();
    }
  }

  private static String line(String datasource, String required, String cost) {
    return "  datasource '" + datasource + "': " + required + "   — without it, " + cost + ".";
  }
}
