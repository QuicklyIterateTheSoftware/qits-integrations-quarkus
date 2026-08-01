package eu.wohlben.qits.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.security.identity.SecurityIdentity;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Reading a machine token off an identity: what is there, what is not, and what that means. */
class MachineIdentityTest {

  @Test
  void aJwtPrincipalIsAMachineAndAnythingElseIsNot() {
    assertTrue(MachineIdentity.isMachine(TestTokens.machine(QitsClaims.CI).build()));
    assertFalse(MachineIdentity.isMachine(TestTokens.user("alice")));
    assertFalse(MachineIdentity.isMachine(TestTokens.anonymous()));
    assertFalse(MachineIdentity.isMachine(null));
  }

  @Test
  void theAudienceIsTheServiceTheTokenIsFor() {
    SecurityIdentity identity = TestTokens.machine(QitsClaims.CI, QitsClaims.CD).build();

    assertTrue(MachineIdentity.hasAudience(identity, QitsClaims.CD));
    assertFalse(MachineIdentity.hasAudience(identity, QitsClaims.ARTIFACTS));
    assertFalse(MachineIdentity.hasAudience(TestTokens.user("alice"), QitsClaims.CD));
  }

  @Test
  void aGrantedClaimReadsBackAndAnUngrantedOneIsEmpty() {
    SecurityIdentity identity =
        TestTokens.machine(QitsClaims.CI, QitsClaims.CI).claim(QitsClaims.PROJECT, "qits").build();

    assertEquals(Optional.of("qits"), MachineIdentity.claim(identity, QitsClaims.PROJECT));
    assertEquals(Optional.empty(), MachineIdentity.claim(identity, QitsClaims.WORKSPACE));
    assertEquals(Optional.empty(), MachineIdentity.claim(TestTokens.user("alice"), QitsClaims.PROJECT));
  }

  @Test
  void anAbsentClaimIsAMismatchNotAWildcard() {
    SecurityIdentity noClaims = TestTokens.machine(QitsClaims.CI, QitsClaims.CI).build();

    assertFalse(MachineIdentity.claimMatches(noClaims, QitsClaims.PROJECT, "qits"));
  }

  @Test
  void aClaimMatchIsExact() {
    SecurityIdentity identity =
        TestTokens.machine(QitsClaims.CI, QitsClaims.CI).claim(QitsClaims.PROJECT, "qits").build();

    assertTrue(MachineIdentity.claimMatches(identity, QitsClaims.PROJECT, "qits"));
    assertFalse(MachineIdentity.claimMatches(identity, QitsClaims.PROJECT, "qits-other"));
    assertFalse(MachineIdentity.claimMatches(identity, QitsClaims.PROJECT, null));
  }

  @Test
  void aWildcardClaimCoversEveryValue() {
    SecurityIdentity everyProject =
        TestTokens.machine(QitsClaims.ARTIFACTS, QitsClaims.CI)
            .claim(QitsClaims.PROJECT, QitsClaims.ANY)
            .build();

    assertTrue(MachineIdentity.claimMatches(everyProject, QitsClaims.PROJECT, "qits"));
    assertTrue(MachineIdentity.claimMatches(everyProject, QitsClaims.PROJECT, "anything-else"));
    assertTrue(MachineIdentity.matchesProject(everyProject, QitsClaims.CI, "qits"));
    // The wildcard is on the claim it was granted for, not on the others.
    assertFalse(MachineIdentity.claimMatches(everyProject, QitsClaims.WORKSPACE, "ws-1"));
    // Still the wrong service.
    assertFalse(MachineIdentity.matchesProject(everyProject, QitsClaims.CD, "qits"));
  }

  @Test
  void askingAboutAStarIsAnOrdinaryQuestion() {
    // The caller names its target; a token for one project does not cover "every project" just
    // because the target spells the wildcard.
    SecurityIdentity oneProject =
        TestTokens.machine(QitsClaims.CI, QitsClaims.CI).claim(QitsClaims.PROJECT, "qits").build();

    assertFalse(MachineIdentity.claimMatches(oneProject, QitsClaims.PROJECT, QitsClaims.ANY));
  }

  @Test
  void theShorthandsWantBothTheAudienceAndTheClaim() {
    SecurityIdentity identity =
        TestTokens.machine(QitsClaims.WORKSPACES, QitsClaims.ARTIFACTS)
            .claim(QitsClaims.WORKSPACE, "ws-1")
            .claim(QitsClaims.BRANCH, "feature/x")
            .build();

    assertTrue(MachineIdentity.matchesWorkspace(identity, QitsClaims.ARTIFACTS, "ws-1"));
    assertTrue(MachineIdentity.matchesBranch(identity, QitsClaims.ARTIFACTS, "feature/x"));
    // Right claim, wrong service: a token minted for artifacts says nothing to qits-ci.
    assertFalse(MachineIdentity.matchesWorkspace(identity, QitsClaims.CI, "ws-1"));
    assertFalse(MachineIdentity.matchesProject(identity, QitsClaims.ARTIFACTS, "ws-1"));
  }
}
