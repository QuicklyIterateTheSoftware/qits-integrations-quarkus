package eu.wohlben.qits.auth;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.json.JsonString;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Reads a validated machine token off a {@link SecurityIdentity}. Static and CDI-free, so a filter,
 * a resource method or a plain unit test can all ask the same questions.
 *
 * <p><b>It validates nothing.</b> By the time an identity reaches here, quarkus-oidc has checked the
 * signature, the issuer and the expiry against qits-idp; this class only reports what the token
 * says. An identity with no {@link JsonWebToken} principal — a forward-auth user, or anonymous — is
 * simply not a machine, which every method below reports as "no" rather than as an error.
 *
 * <p>Most callers want {@link MachineAuth}, which adds the rollout gate and turns a "no" into a
 * status code. Use these methods directly when the decision is more than an equality check.
 */
public final class MachineIdentity {

  /** True when the identity carries a validated machine token. */
  public static boolean isMachine(SecurityIdentity identity) {
    return token(identity) != null;
  }

  /** The token's {@code aud} values, empty when the identity is not a machine. */
  public static Set<String> audiences(SecurityIdentity identity) {
    JsonWebToken jwt = token(identity);
    if (jwt == null || jwt.getAudience() == null) {
      return Set.of();
    }
    return jwt.getAudience();
  }

  /** True when the token is addressed to {@code audience} — one of {@link QitsClaims#SERVICE_IDS}. */
  public static boolean hasAudience(SecurityIdentity identity, String audience) {
    return audiences(identity).contains(audience);
  }

  /**
   * A structured claim's value — one of {@link QitsClaims#NAMES}. Empty when the identity is not a
   * machine, or when the claim was not granted to the client.
   */
  public static Optional<String> claim(SecurityIdentity identity, String name) {
    JsonWebToken jwt = token(identity);
    if (jwt == null) {
      return Optional.empty();
    }
    return jwt.claim(name).map(MachineIdentity::asString).filter(v -> !v.isBlank());
  }

  /**
   * True when the claim is present and equal to {@code expected}. An absent claim is a mismatch: a
   * token that was never granted a {@code project} may not act on one.
   */
  public static boolean claimMatches(SecurityIdentity identity, String name, String expected) {
    return expected != null && claim(identity, name).filter(expected::equals).isPresent();
  }

  /** Shorthand for the common check: right audience and right {@code project}. */
  public static boolean matchesProject(
      SecurityIdentity identity, String audience, String project) {
    return hasAudience(identity, audience) && claimMatches(identity, QitsClaims.PROJECT, project);
  }

  /** Shorthand for the common check: right audience and right {@code workspace}. */
  public static boolean matchesWorkspace(
      SecurityIdentity identity, String audience, String workspace) {
    return hasAudience(identity, audience)
        && claimMatches(identity, QitsClaims.WORKSPACE, workspace);
  }

  /** Shorthand for the common check: right audience and right {@code branch}. */
  public static boolean matchesBranch(SecurityIdentity identity, String audience, String branch) {
    return hasAudience(identity, audience) && claimMatches(identity, QitsClaims.BRANCH, branch);
  }

  private static JsonWebToken token(SecurityIdentity identity) {
    return identity != null && identity.getPrincipal() instanceof JsonWebToken jwt ? jwt : null;
  }

  // quarkus-oidc hands back a plain String; smallrye-jwt hands back a JSON-P value. Both are legal
  // MP-JWT, so both are read rather than one being assumed.
  private static String asString(Object value) {
    return value instanceof JsonString json ? json.getString() : String.valueOf(value);
  }

  private MachineIdentity() {}
}
