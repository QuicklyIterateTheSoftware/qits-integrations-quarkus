package eu.wohlben.qits.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import org.junit.jupiter.api.Test;

/**
 * The tier across an HTTP hop, proved on a real wire and with <b>no {@code qits.environment}
 * configured</b> — this suite's arrangement, and a platform-tier deployment's. The client filter
 * therefore stamps the {@code "platform"} fallback, which is the half of the resolution rule the
 * configured suite ({@link EnvironmentRestPropagationDevTierTest}) cannot see.
 *
 * <p>The no-header requests loop where staleness is the question, because thread hygiene on a
 * pooled worker cannot be pinned to one request: a leak shows up as some <em>later</em> request
 * answering with an earlier caller's tier.
 */
@QuarkusTest
class EnvironmentRestPropagationTest {

  @TestHTTPResource URI base;

  private EnvironmentProbeClient client() {
    return QuarkusRestClientBuilder.newBuilder().baseUri(base).build(EnvironmentProbeClient.class);
  }

  /** The feature, whole: config here (its fallback), ambient value there, nobody passed anything. */
  @Test
  void anUnconfiguredProcessArrivesAsThePlatformTier() {
    assertEquals(EnvironmentHeader.PLATFORM, client().callerTier());
    assertNull(CallerEnvironment.current(), "the calling thread is left as it was found");
  }

  /** A caller that names a tier explicitly is proxying on another's behalf, and wins. */
  @Test
  void aHeaderTheCallerSetItselfBeatsTheConfiguredTier() {
    assertEquals("dev", client().callerTierWith("dev"));
  }

  /** Advisory means lenient: a blank header reads exactly like none at all. */
  @Test
  void aBlankHeaderIsServedAsNoCallerTier() {
    assertEquals("null", client().callerTierWith("  "));
  }

  /**
   * The hygiene assertion. Workers are pooled and long-lived, so the request filter's
   * establish-even-when-absent and the response filter's restore have one observable consequence
   * between them: no request ever answers with a tier a previous request's caller stated.
   */
  @Test
  void aRequestWithoutTheHeaderNeverSeesAPreviousCallersTier() {
    EnvironmentProbeClient client = client();
    for (int i = 0; i < 8; i++) {
      assertEquals("prod", client.callerTierWith("prod"));
      assertEquals(
          "null",
          client.callerTierWith(""),
          "a pooled worker lent one caller's tier to the next");
    }
  }
}
