package eu.wohlben.qits.servicemock.idp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;

/**
 * Compact JWS assembly — the ~40 lines that keep this module free of a JWT stack: JSON via
 * Jackson, base64url without padding, {@code SHA256withRSA} via JCA. Correctness is pinned by the
 * module's self-test, which verifies the output through jose4j (the library quarkus-oidc itself
 * is built on).
 */
final class Jws {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

  private Jws() {}

  /** {@code base64url(header) . base64url(claims) . base64url(RS256 signature)}. */
  static String sign(Map<String, Object> header, Map<String, Object> claims, PrivateKey key) {
    String signingInput = encode(header) + "." + encode(claims);
    try {
      Signature signature = Signature.getInstance("SHA256withRSA");
      signature.initSign(key);
      signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
      return signingInput + "." + BASE64URL.encodeToString(signature.sign());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("RS256 signing failed", e);
    }
  }

  private static String encode(Map<String, Object> json) {
    try {
      return BASE64URL.encodeToString(MAPPER.writeValueAsBytes(json));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("unserializable JWS part: " + json, e);
    }
  }
}
