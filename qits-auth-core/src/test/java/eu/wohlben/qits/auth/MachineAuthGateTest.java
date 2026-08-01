package eu.wohlben.qits.auth;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * The shipped default, through a real request: the gate is off, so a guarded endpoint serves a
 * caller that presents no machine token at all. This is the posture every service adopts the lib
 * in, and it is why adoption changes no behaviour.
 */
@QuarkusTest
class MachineAuthGateTest {

  @Test
  void theShippedDefaultLeavesAGuardedEndpointOpen() {
    given().when().get("/guarded/qits").then().statusCode(200).body(equalTo("open"));
  }
}
