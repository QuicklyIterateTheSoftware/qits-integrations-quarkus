package eu.wohlben.qits.auth;

import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Completes {@link ForwardAuthMechanism}'s trusted request into a {@link SecurityIdentity}: the
 * principal is the header-supplied username and the roles are the comma-separated values asserted
 * in {@code X-Qits-Roles}. The platform edge derives both from the same introspected session, and
 * qits-gateway strips every client-supplied {@code X-Qits-*} header before re-asserting them.
 * Consumers can therefore use Jakarta's standard {@code @RolesAllowed} at their REST boundaries.
 *
 * <p>The principal is the <b>name</b>, not the stable subject id: the id travels alongside it as
 * {@code X-Qits-User-Id} and nothing reads it yet, while the name is what the platform's existing
 * audit rows hold.
 */
@ApplicationScoped
public class ForwardAuthIdentityProvider implements IdentityProvider<ForwardedAuthenticationRequest> {

  @Override
  public Class<ForwardedAuthenticationRequest> getRequestType() {
    return ForwardedAuthenticationRequest.class;
  }

  @Override
  public Uni<SecurityIdentity> authenticate(
      ForwardedAuthenticationRequest request, AuthenticationRequestContext context) {
    return Uni.createFrom()
        .item(
            QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(request.user()))
                .addRoles(request.roles())
                .build());
  }
}
