package eu.wohlben.qits.auth;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.LinkedHashSet;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Turns the {@code X-Qits-User} header qits-gateway asserts into the consuming service's {@link
 * SecurityIdentity}. It is the whole of a service's relationship with <b>user</b> authentication.
 *
 * <p><b>The service authenticates nothing.</b> The gateway performs the login, strips every
 * client-supplied {@code X-Qits-*} header from every inbound request, and injects the resulting
 * identity — so the header is believed here unconditionally, which is exactly what makes the
 * stripping load-bearing rather than tidy. There is no variant to choose and no scheme to
 * configure; a service alone is, by construction, unauthenticated, and its safety is the gateway's
 * and the network's property, never its own.
 *
 * <p><b>Anonymous is not a denial.</b> Nothing here carries an authorization policy, so a missing
 * header yields an anonymous identity and the request proceeds. That is the intended behaviour, not
 * an oversight: the identity exists to name the actor, and reaching the service at all already
 * implies you are inside the trusted network. {@link #getChallenge} is present because the
 * interface demands it.
 *
 * <p>Machine identity is a separate track and does not pass through here: a bearer is validated by
 * the service's own quarkus-oidc, and {@link MachineAuth} decides what its claims permit.
 *
 * <p>See migration-auth-plan.md §2 and §12.
 */
@ApplicationScoped
public class ForwardAuthMechanism implements HttpAuthenticationMechanism {

  @ConfigProperty(name = "qits.auth.forward.user-header")
  String userHeader;

  @ConfigProperty(name = "qits.auth.forward.roles-header")
  String rolesHeader;

  @ConfigProperty(name = "qits.auth.forward.dev-user")
  Optional<String> devUser;

  @ConfigProperty(name = "qits.auth.forward.dev-roles")
  Optional<String> devRoles;

  @Override
  public Uni<SecurityIdentity> authenticate(
      RoutingContext context, IdentityProviderManager identityProviderManager) {
    String user = context.request().getHeader(userHeader);
    String assertedRoles = context.request().getHeader(rolesHeader);
    if (user == null || user.isBlank()) {
      // The %dev/%test-scoped synthetic identity — no gateway in front of dev mode or a test suite,
      // and this is what keeps a service's suite runnable with no auth setup at all. LaunchMode
      // guarded on top of the config scoping: a prod build stays anonymous even if the property
      // leaks in via env.
      if (devUser.isEmpty() || LaunchMode.current() == LaunchMode.NORMAL) {
        return Uni.createFrom().nullItem();
      }
      user = devUser.get();
      assertedRoles = devRoles.orElse(null);
    }
    // Through the IdentityProviderManager (not building the identity here) so
    // SecurityIdentityAugmentors keep working.
    return identityProviderManager.authenticate(
        HttpSecurityUtils.setRoutingContextAttribute(
            new ForwardedAuthenticationRequest(user, roles(assertedRoles)),
            context));
  }

  @Override
  public Uni<ChallengeData> getChallenge(RoutingContext context) {
    return Uni.createFrom().item(new ChallengeData(401, null, null));
  }

  @Override
  public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
    return Set.of(ForwardedAuthenticationRequest.class);
  }

  static Set<String> roles(String header) {
    Set<String> roles = new LinkedHashSet<>();
    if (header == null || header.isBlank()) {
      return roles;
    }
    for (String role : header.split(",")) {
      String read = role.strip();
      if (!read.isEmpty()) {
        roles.add(read);
      }
    }
    return roles;
  }
}
