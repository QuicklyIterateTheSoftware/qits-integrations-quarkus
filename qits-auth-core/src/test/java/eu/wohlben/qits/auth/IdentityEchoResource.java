package eu.wohlben.qits.auth;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Set;

/**
 * Test-only: reports what the request resolved to, so the header contract can be asserted directly
 * rather than inferred from some endpoint that happens to consume an identity. A copy of the
 * fixture each service already has, kept here so the mechanism is proven in the jar that ships it.
 */
@Path("/test-identity")
@Produces(MediaType.APPLICATION_JSON)
public class IdentityEchoResource {

  @Inject SecurityIdentity identity;

  public record Identity(boolean anonymous, String principal, Set<String> roles) {}

  @GET
  public Identity get() {
    return new Identity(
        identity.isAnonymous(),
        identity.getPrincipal() == null ? null : identity.getPrincipal().getName(),
        identity.getRoles());
  }
}
