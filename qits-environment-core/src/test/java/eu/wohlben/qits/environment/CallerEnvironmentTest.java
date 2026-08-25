package eu.wohlben.qits.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** The scope discipline, without a wire: restore-not-clear, nesting, and the throwing body. */
class CallerEnvironmentTest {

  @Test
  void withEstablishesTheValueForTheRegionAndOnlyTheRegion() {
    assertNull(CallerEnvironment.current());
    CallerEnvironment.with("dev", () -> assertEquals("dev", CallerEnvironment.current()));
    assertNull(CallerEnvironment.current());
  }

  @Test
  void nestedScopesRestoreTheOuterOne() {
    CallerEnvironment.with(
        "dev",
        () -> {
          CallerEnvironment.with("prod", () -> assertEquals("prod", CallerEnvironment.current()));
          assertEquals("dev", CallerEnvironment.current());
        });
  }

  @Test
  void aBodyThatThrowsStillRestores() {
    assertThrows(
        IllegalStateException.class,
        () ->
            CallerEnvironment.with(
                "dev",
                () -> {
                  throw new IllegalStateException("boom");
                }));
    assertNull(CallerEnvironment.current());
  }

  @Test
  void withNullIsTheDeliberateDetach() {
    CallerEnvironment.with(
        "dev",
        () -> CallerEnvironment.with(null, () -> assertNull(CallerEnvironment.current())));
  }

  @Test
  void parseIsLenient() {
    assertNull(EnvironmentHeader.parse(null));
    assertNull(EnvironmentHeader.parse("   "));
    assertEquals("dev", EnvironmentHeader.parse(" dev "));
  }
}
