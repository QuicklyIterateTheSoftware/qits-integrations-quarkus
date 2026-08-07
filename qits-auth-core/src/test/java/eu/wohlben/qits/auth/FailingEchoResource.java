package eu.wohlben.qits.auth;

import io.quarkus.oidc.OIDCException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

/**
 * Test-only: throws the failures {@link IssuerUnreachable} has to tell apart, from inside a real
 * request, so the assertion is on the STATUS a caller sees rather than on the predicate alone. An
 * exception out of a resource method reaches the Vert.x failure handlers — the same path the real
 * OIDC failure takes, which is why it is provoked here and not by calling the handler directly.
 */
@Path("/failing")
public class FailingEchoResource {

  @GET
  @Path("/{kind}")
  public String fail(@PathParam("kind") String kind) {
    throw switch (kind) {
      // The real shape: the tenant could not be initialized because nothing answered.
      case "unreachable" -> new OIDCException(
          "OIDC Server is not available", new java.net.ConnectException("Connection refused"));
      // An OIDC failure that is this service's own fault. Retrying cannot fix it, so it stays 500.
      case "misconfigured" -> new OIDCException("Configured audience is not valid");
      // Connectivity with nothing to do with the issuer — a downstream call. Also stays 500.
      case "downstream" -> new IllegalStateException(
          "the git host did not answer", new java.net.ConnectException("Connection refused"));
      default -> new IllegalArgumentException("unknown kind " + kind);
    };
  }
}
