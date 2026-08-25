package eu.wohlben.qits.environment;

/**
 * The HTTP header that carries an environment tier across a service boundary, and the config
 * property it is resolved from — the wire and configuration spellings of one concept.
 *
 * <p>The platform has environments ({@code dev}, {@code prod}) and one platform plane serving all
 * of them. qits-deployments injects {@code QITS_ENVIRONMENT=&lt;name&gt;} into every
 * environment-tier service and deliberately injects nothing into a platform-tier one — the absence
 * is load-bearing on the deployer's own boot path, so it is never papered over with an env var.
 * MicroProfile Config reads that variable as {@link #PROPERTY}, and the resolution rule is applied
 * in code, in every stamping point alike: {@code qits.environment}, or {@link #PLATFORM} where a
 * deployment injects none, because a service the deployer gives no tier is, by the platform's own
 * definition, serving every tier.
 *
 * <p><b>The name is inside the edge's reserved namespace on purpose.</b> qits-platform-edge strips
 * every client-supplied {@code X-Qits-*} header before it proxies — a case-insensitive prefix
 * match, not a list — so an outside caller cannot claim a tier. Service-to-service traffic does not
 * pass the edge and carries the header untouched.
 *
 * <p>{@link EnvironmentClientFilter} and {@link EnvironmentServerFilter} are the automatic path. A
 * caller that builds its requests by hand — most of this platform speaks {@code
 * java.net.http.HttpClient}, not the REST client — stamps the same header itself:
 *
 * <pre>{@code
 * builder.header(EnvironmentHeader.NAME,
 *     ConfigProvider.getConfig()
 *         .getOptionalValue(EnvironmentHeader.PROPERTY, String.class)
 *         .orElse(EnvironmentHeader.PLATFORM));
 * }</pre>
 *
 * <p><b>One deliberate duplication:</b> qits-eventstream stamps the same property onto every
 * published event envelope, and its extraction rule forbids importing this class — the string
 * {@code "qits.environment"} is spelled in both repositories, so grep both on a rename.
 */
public final class EnvironmentHeader {

  public static final String NAME = "X-Qits-Environment";

  /** The MicroProfile spelling of the deployer's {@code QITS_ENVIRONMENT}. */
  public static final String PROPERTY = "qits.environment";

  /** The tier of a process no deployment gave one: the platform plane serves every tier. */
  public static final String PLATFORM = "platform";

  private EnvironmentHeader() {}

  /**
   * The header's value as a tier, or {@code null} for absent or blank. Lenient because the caller's
   * tier is advisory: a request whose header says nothing readable must be served exactly as one
   * that carries none. No shape check here — the receiving side treats the value as data, and the
   * one place shape matters (qits-events' write path) validates for itself.
   */
  static String parse(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }
}
