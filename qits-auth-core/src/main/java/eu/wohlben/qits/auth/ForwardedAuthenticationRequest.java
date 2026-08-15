package eu.wohlben.qits.auth;

import io.quarkus.security.identity.request.BaseAuthenticationRequest;
import java.util.Set;

/** The user identity asserted by the platform edge and re-asserted by qits-gateway. */
final class ForwardedAuthenticationRequest extends BaseAuthenticationRequest {

  private final String user;
  private final Set<String> roles;

  ForwardedAuthenticationRequest(String user, Set<String> roles) {
    this.user = user;
    this.roles = Set.copyOf(roles);
  }

  String user() {
    return user;
  }

  Set<String> roles() {
    return roles;
  }
}
