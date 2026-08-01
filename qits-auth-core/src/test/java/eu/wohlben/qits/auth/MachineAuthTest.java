package eu.wohlben.qits.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.Test;

/**
 * The gate and the checks behind it. The gate half is the important half: off must mean "exactly as
 * today" for every caller, however wrong the identity looks.
 */
class MachineAuthTest {

  private static final SecurityIdentity CI_TOKEN_FOR_QITS =
      TestTokens.machine(QitsClaims.CI, QitsClaims.CI).claim(QitsClaims.PROJECT, "qits").build();

  private static MachineAuth gateOff(SecurityIdentity identity) {
    return new MachineAuth(false, QitsClaims.CI, identity);
  }

  private static MachineAuth gateOn(SecurityIdentity identity) {
    return new MachineAuth(true, QitsClaims.CI, identity);
  }

  @Test
  void gateOffPassesEveryCallerThroughUntouched() {
    for (SecurityIdentity identity :
        new SecurityIdentity[] {
          TestTokens.anonymous(),
          TestTokens.user("alice"),
          TestTokens.machine("someone-else", QitsClaims.ARTIFACTS).build()
        }) {
      MachineAuth auth = gateOff(identity);
      assertFalse(auth.enforced());
      assertDoesNotThrow(auth::require);
      assertDoesNotThrow(() -> auth.requireProject("qits"));
      assertDoesNotThrow(() -> auth.requireWorkspace("ws-1"));
      assertDoesNotThrow(() -> auth.requireBranch("main"));
      assertTrue(auth.permits(QitsClaims.PROJECT, "qits"));
    }
  }

  @Test
  void gateOnAcceptsTheMatchingToken() {
    MachineAuth auth = gateOn(CI_TOKEN_FOR_QITS);

    assertTrue(auth.enforced());
    assertDoesNotThrow(auth::require);
    assertDoesNotThrow(() -> auth.requireProject("qits"));
    assertTrue(auth.permits(QitsClaims.PROJECT, "qits"));
  }

  @Test
  void gateOnRejectsAMismatchedProject() {
    MachineAuth auth = gateOn(CI_TOKEN_FOR_QITS);

    assertThrows(ForbiddenException.class, () -> auth.requireProject("some-other-project"));
    assertFalse(auth.permits(QitsClaims.PROJECT, "some-other-project"));
  }

  @Test
  void gateOnRejectsAnUngrantedClaim() {
    MachineAuth auth = gateOn(TestTokens.machine(QitsClaims.CI, QitsClaims.CI).build());

    assertThrows(ForbiddenException.class, () -> auth.requireProject("qits"));
  }

  @Test
  void gateOnRejectsATokenMeantForAnotherService() {
    MachineAuth auth =
        gateOn(
            TestTokens.machine(QitsClaims.CD, QitsClaims.ARTIFACTS)
                .claim(QitsClaims.PROJECT, "qits")
                .build());

    // A 403, not a 401: the caller authenticated, it just is not talking to its own service.
    assertThrows(ForbiddenException.class, auth::require);
    assertThrows(ForbiddenException.class, () -> auth.requireProject("qits"));
  }

  @Test
  void gateOnRejectsANonMachineCaller() {
    // A 401, not a 403: no machine credential was presented at all, so the answer is "show one".
    assertThrows(UnauthorizedException.class, () -> gateOn(TestTokens.user("alice")).require());
    assertThrows(
        UnauthorizedException.class, () -> gateOn(TestTokens.anonymous()).requireProject("qits"));
    assertFalse(gateOn(TestTokens.anonymous()).permits(QitsClaims.PROJECT, "qits"));
  }

  @Test
  void turningTheGateOnWithNoAudienceFailsAtStartup() {
    // Failing the deploy beats accepting a token minted for a different service.
    MachineAuth auth = new MachineAuth(true, null, TestTokens.anonymous());

    assertThrows(IllegalStateException.class, () -> auth.validateConfig(null));
    assertDoesNotThrow(() -> new MachineAuth(false, null, TestTokens.anonymous()).validateConfig(null));
  }
}
