package eu.wohlben.qits.environment;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * The outgoing half: every REST-client request this process sends says which tier it serves, as
 * the {@link EnvironmentHeader#NAME} header.
 *
 * <p>{@code @Provider} is the whole registration — Quarkus applies an annotated client filter to
 * every REST client in the application, injected or built. A consumer adds nothing; a consumer
 * without the REST client on its classpath simply never instantiates this class.
 *
 * <p><b>A header the caller set itself wins.</b> The filter fills the header only when it is
 * absent — a caller that names a tier explicitly is proxying on another's behalf and knows
 * something this process's configuration does not.
 *
 * <p>Unlike the causation filter beside it in spirit, the value here is not request-scoped state:
 * it is the process's own tier, {@code qits.environment} or {@code "platform"} where a deployment
 * injects none — the one resolution rule, applied at every stamping point alike (qits-eventstream
 * applies the same one to every published event). So the header is on <em>every</em> request, and
 * an absent header on the receiving side always means an unstamped caller, never a platform one.
 * Read through {@link ConfigProvider} rather than injected, so this class needs no CDI to compile
 * and works however it is instantiated; the config's own caching makes the per-request read free.
 */
@Provider
@Priority(Priorities.HEADER_DECORATOR)
public class EnvironmentClientFilter implements ClientRequestFilter {

  @Override
  public void filter(ClientRequestContext requestContext) {
    if (requestContext.getHeaders().containsKey(EnvironmentHeader.NAME)) {
      return;
    }
    String tier =
        ConfigProvider.getConfig()
            .getOptionalValue(EnvironmentHeader.PROPERTY, String.class)
            .orElse(EnvironmentHeader.PLATFORM);
    requestContext.getHeaders().add(EnvironmentHeader.NAME, tier);
  }
}
