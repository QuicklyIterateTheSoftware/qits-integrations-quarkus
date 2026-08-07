package eu.wohlben.qits.auth;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.oidc.OIDCException;
import io.quarkus.test.junit.QuarkusTest;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * What a caller is told when the token plane is the thing that failed.
 *
 * <p>The distinction under test is 503-versus-500, and it is not cosmetic here: qits-ci announces
 * green builds to qits-platform-deployments fire-and-forget and swallows the failure at debug. A 500
 * on that path is a deployment that never happens and says nothing; a 503 with Retry-After is the
 * answer a retrying caller can act on.
 */
@QuarkusTest
class IssuerUnreachableTest {

  @Test
  void anUnreachableIssuerIsRetryableRatherThanTheCallersFault() {
    given()
        .when()
        .get("/failing/unreachable")
        .then()
        .statusCode(503)
        .header("Retry-After", equalTo("5"));
  }

  @Test
  void anOidcFailureThisServiceCausedStaysItsOwnFault() {
    // Retrying cannot fix a wrong audience, and telling a caller to try again would hide it.
    given().when().get("/failing/misconfigured").then().statusCode(500);
  }

  @Test
  void connectivityToSomethingOtherThanTheIssuerIsNotTheIssuersFault() {
    // A ConnectException alone must not be read as "the token plane is down" — the git host being
    // unreachable is a different failure with a different owner.
    given().when().get("/failing/downstream").then().statusCode(500);
  }

  @Test
  void bothHalvesAreRequired() {
    assertTrue(IssuerUnreachable.causedByUnreachableIssuer(
        new OIDCException("unavailable", new ConnectException("refused"))));
    assertTrue(IssuerUnreachable.causedByUnreachableIssuer(
        new OIDCException("unavailable", new UnknownHostException("qits-idp"))));
    assertTrue(IssuerUnreachable.causedByUnreachableIssuer(
        new RuntimeException("wrapped", new OIDCException("unavailable", new TimeoutException()))));
    assertFalse(IssuerUnreachable.causedByUnreachableIssuer(new OIDCException("bad audience")));
    assertFalse(IssuerUnreachable.causedByUnreachableIssuer(new ConnectException("refused")));
    assertFalse(IssuerUnreachable.causedByUnreachableIssuer(null));
  }

  @Test
  void aCyclicCauseChainDoesNotHangTheRequestThread() {
    // Java refuses self-causation, so the cycle that CAN happen is A -> B -> A. The walk runs on an
    // event-loop thread inside a failure handler, where spinning is not one slow request but the
    // whole process, so it is bounded rather than "until null".
    Throwable first = new IllegalStateException("first");
    Throwable second = new IllegalStateException("second", first);
    first.initCause(second);
    assertFalse(IssuerUnreachable.causedByUnreachableIssuer(first));
  }

  @Test
  void aCyclicChainThatDoesContainBothHalvesIsStillFound() {
    // The bound must not become a way to miss a real answer: the halves are found on the way round,
    // not after it.
    Throwable connect = new ConnectException("refused");
    Throwable oidc = new OIDCException("unavailable", connect);
    connect.initCause(oidc);
    assertTrue(IssuerUnreachable.causedByUnreachableIssuer(oidc));
  }
}
