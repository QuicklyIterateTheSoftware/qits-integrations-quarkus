package eu.wohlben.qits.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The configured half of the resolution rule: with {@code qits.environment} set — what an
 * environment-tier deployment's injected {@code QITS_ENVIRONMENT} amounts to — the client filter
 * stamps that tier, not the fallback.
 */
@QuarkusTest
@TestProfile(EnvironmentRestPropagationDevTierTest.DevTier.class)
class EnvironmentRestPropagationDevTierTest {

  public static class DevTier implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(EnvironmentHeader.PROPERTY, "dev");
    }
  }

  @TestHTTPResource URI base;

  @Test
  void aConfiguredProcessArrivesAsItsOwnTier() {
    EnvironmentProbeClient client =
        QuarkusRestClientBuilder.newBuilder().baseUri(base).build(EnvironmentProbeClient.class);

    assertEquals("dev", client.callerTier());
  }
}
