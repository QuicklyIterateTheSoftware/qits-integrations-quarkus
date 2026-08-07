package io.quarkus.oidc;

/**
 * Test-only stand-in for the real quarkus-oidc exception, under its real name.
 *
 * <p>{@code IssuerUnreachable} matches this class by NAME so that quarkus-oidc stays out of this
 * library's poms — see the note there. That decision is only honest if the test drives the same
 * string the runtime will, which is why this lives in {@code io.quarkus.oidc} rather than being a
 * local class the matcher was taught about. A rename upstream then fails here, which is the point.
 */
public class OIDCException extends RuntimeException {

  public OIDCException(String message, Throwable cause) {
    super(message, cause);
  }

  public OIDCException(String message) {
    super(message);
  }
}
