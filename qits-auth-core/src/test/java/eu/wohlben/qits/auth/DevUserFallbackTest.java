package eu.wohlben.qits.auth;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * The other half of the mechanism: with no profile override, {@code %test.qits.auth.forward.dev-user}
 * from this jar's own microprofile-config.properties supplies an identity, which is what lets a
 * consuming service's suite run with no auth setup at all.
 */
@QuarkusTest
class DevUserFallbackTest {

  @Test
  void noHeaderFallsBackToTheDevUser() {
    given()
        .when()
        .get("/test-identity")
        .then()
        .statusCode(200)
        .body("anonymous", equalTo(false))
        .body("principal", equalTo("dev"));
  }

  @Test
  void aRealHeaderStillWins() {
    given()
        .header("X-Qits-User", "alice")
        .when()
        .get("/test-identity")
        .then()
        .statusCode(200)
        .body("principal", equalTo("alice"));
  }
}
