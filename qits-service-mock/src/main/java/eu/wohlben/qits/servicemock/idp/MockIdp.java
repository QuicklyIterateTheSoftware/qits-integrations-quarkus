package eu.wohlben.qits.servicemock.idp;

import eu.wohlben.qits.servicemock.MockService;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The mock of qits-platform-idp: a {@link MockService} plus the one thing canned JSON cannot fake
 * — key material. It generates an RSA keypair, stubs {@code GET /idp/jwks} with the matching JWK
 * set (shaped exactly like the real {@code idp/control/Jwks}) and a minimal {@code
 * /idp/.well-known/openid-configuration}, and mints RS256 tokens signed by that keypair via
 * {@link #token()}. Recording, stubbing and the classloader-safe {@code ensureStarted}/{@code
 * attach} pattern are all the generic {@link MockService}'s; this class only adds the crypto —
 * which is why it parks the (encoded) keypair in system properties of its own, so an attached
 * handle signs with the same key the served JWKS published.
 */
public final class MockIdp implements AutoCloseable {

  /** The service this impersonates — also the {@link MockService#ensureStarted(String)} key. */
  public static final String SERVICE_NAME = "qits-platform-idp";

  private static final String KID_PROPERTY = "qits.servicemock.idp.kid";
  private static final String PRIVATE_KEY_PROPERTY = "qits.servicemock.idp.private-key";
  private static final String PUBLIC_KEY_PROPERTY = "qits.servicemock.idp.public-key";

  private final MockService service;
  private final String kid;
  private final KeyPair keyPair;

  private MockIdp(MockService service, String kid, KeyPair keyPair) {
    this.service = service;
    this.kid = kid;
    this.keyPair = keyPair;
  }

  /** Start an owned instance on a random port with a fresh RSA-2048 keypair. */
  public static MockIdp start() {
    return stubbed(MockService.start(SERVICE_NAME));
  }

  /**
   * Start once per JVM and park port + keypair + kid in system properties, so any later {@link
   * #attach()} — including from another classloader's copy of this class — reaches the same
   * server and signs with the same key. The instance lives until the JVM exits.
   */
  public static synchronized MockIdp ensureStarted() {
    if (System.getProperty(KID_PROPERTY) != null) {
      return attach();
    }
    MockIdp idp = stubbed(MockService.ensureStarted(SERVICE_NAME));
    Base64.Encoder base64 = Base64.getEncoder();
    System.setProperty(KID_PROPERTY, idp.kid);
    System.setProperty(
        PRIVATE_KEY_PROPERTY, base64.encodeToString(idp.keyPair.getPrivate().getEncoded()));
    System.setProperty(
        PUBLIC_KEY_PROPERTY, base64.encodeToString(idp.keyPair.getPublic().getEncoded()));
    return idp;
  }

  /** Rebuild a handle onto the {@link #ensureStarted()} instance from the parked properties. */
  public static MockIdp attach() {
    String kid = System.getProperty(KID_PROPERTY);
    if (kid == null) {
      throw new IllegalStateException("no mock idp started in this JVM (ensureStarted() first)");
    }
    Base64.Decoder base64 = Base64.getDecoder();
    try {
      KeyFactory rsa = KeyFactory.getInstance("RSA");
      KeyPair keyPair =
          new KeyPair(
              rsa.generatePublic(
                  new X509EncodedKeySpec(base64.decode(System.getProperty(PUBLIC_KEY_PROPERTY)))),
              rsa.generatePrivate(
                  new PKCS8EncodedKeySpec(
                      base64.decode(System.getProperty(PRIVATE_KEY_PROPERTY)))));
      return new MockIdp(MockService.attach(SERVICE_NAME), kid, keyPair);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("failed to rebuild the parked mock idp keypair", e);
    }
  }

  /** Generate the keypair and register the two documents an OIDC consumer reads as plain stubs. */
  private static MockIdp stubbed(MockService service) {
    KeyPair keyPair = generateKeyPair();
    MockIdp idp = new MockIdp(service, randomKid(), keyPair);
    service.stub("GET", "/idp/jwks", idp.jwksDocument());
    service.stub("GET", "/idp/.well-known/openid-configuration", idp.discoveryDocument());
    return idp;
  }

  /** The OIDC base URL, e.g. {@code http://localhost:49213/idp} — what an auth-server-url wants. */
  public String baseUrl() {
    return service.baseUrl() + "/idp";
  }

  /** The issuer minted into tokens; equals {@link #baseUrl()}, like the real idp's default. */
  public String issuer() {
    return baseUrl();
  }

  public String kid() {
    return kid;
  }

  public RSAPublicKey publicKey() {
    return (RSAPublicKey) keyPair.getPublic();
  }

  /** The underlying generic mock — for stubbing further idp routes a test wants. */
  public MockService service() {
    return service;
  }

  /** Mint an RS256 token signed by this mock's key (unless overridden on the builder). */
  public TokenBuilder token() {
    return new TokenBuilder();
  }

  /** Every request the mock answered (control traffic excluded), in arrival order. */
  public List<MockService.RecordedRequest> recordedRequests() {
    return service.recordedRequests();
  }

  /** Clear the recordings (the keypair, kid and stubs stay stable). */
  public void reset() {
    service.reset();
  }

  /** Stop an owned server; a no-op on an {@link #attach()}ed handle. */
  @Override
  public void close() {
    service.close();
  }

  // --- the idp documents ---------------------------------------------------------------------

  private Map<String, Object> jwksDocument() {
    // Shape and member order mirror the real idp's Jwks: kty, use, alg, kid, n, e.
    Map<String, Object> jwk = new LinkedHashMap<>();
    jwk.put("kty", "RSA");
    jwk.put("use", "sig");
    jwk.put("alg", "RS256");
    jwk.put("kid", kid);
    jwk.put("n", base64Url(publicKey().getModulus()));
    jwk.put("e", base64Url(publicKey().getPublicExponent()));
    return Map.of("keys", List.of(jwk));
  }

  private Map<String, Object> discoveryDocument() {
    Map<String, Object> discovery = new LinkedHashMap<>();
    discovery.put("issuer", issuer());
    discovery.put("authorization_endpoint", issuer() + "/authorize");
    discovery.put("token_endpoint", issuer() + "/token");
    discovery.put("jwks_uri", issuer() + "/jwks");
    discovery.put("id_token_signing_alg_values_supported", List.of("RS256"));
    return discovery;
  }

  /**
   * Base64url of the unsigned big-endian value — {@link BigInteger#toByteArray()} prefixes a zero
   * byte whenever the top bit is set; leave it in and every consumer computes a different modulus
   * than the one that signed (the footgun the real idp's Jwks documents).
   */
  private static String base64Url(BigInteger value) {
    byte[] bytes = value.toByteArray();
    int start = 0;
    while (start < bytes.length - 1 && bytes[start] == 0) {
      start++;
    }
    byte[] unsigned = new byte[bytes.length - start];
    System.arraycopy(bytes, start, unsigned, 0, unsigned.length);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(unsigned);
  }

  private static KeyPair generateKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("RSA keypair generation failed", e);
    }
  }

  private static String randomKid() {
    byte[] bytes = new byte[8];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /**
   * Builds one RS256 compact JWS. Claim discipline mirrors the real TokenService: {@code aud} is
   * always a JSON array, roles travel in {@code groups} (the MP-JWT default quarkus-oidc maps to
   * {@code SecurityIdentity} roles), and the {@code kid} rides the JWS header.
   */
  public final class TokenBuilder {

    private String subject = "mock-idp-client";
    private List<String> audience = List.of();
    private List<String> groups = List.of();
    private final Map<String, Object> extraClaims = new LinkedHashMap<>();
    private Duration ttl = Duration.ofMinutes(5);
    private String headerKid = kid;
    private PrivateKey signingKey = keyPair.getPrivate();

    private TokenBuilder() {}

    public TokenBuilder subject(String sub) {
      this.subject = sub;
      return this;
    }

    public TokenBuilder audience(String... audiences) {
      this.audience = List.of(audiences);
      return this;
    }

    public TokenBuilder groups(String... groups) {
      this.groups = List.of(groups);
      return this;
    }

    public TokenBuilder claim(String name, Object value) {
      extraClaims.put(name, value);
      return this;
    }

    /** Time to live; a negative duration mints an already-expired token for negative tests. */
    public TokenBuilder ttl(Duration ttl) {
      this.ttl = ttl;
      return this;
    }

    /** Override the JWS-header {@code kid} — a wrong-kid negative test. */
    public TokenBuilder kid(String kid) {
      this.headerKid = kid;
      return this;
    }

    /** Sign with a throwaway keypair the JWKS never published — a stranger's token. */
    public TokenBuilder signedByUnknownKey() {
      this.signingKey = generateKeyPair().getPrivate();
      return this;
    }

    public String mint() {
      Map<String, Object> header = new LinkedHashMap<>();
      header.put("alg", "RS256");
      header.put("typ", "JWT");
      header.put("kid", headerKid);

      Instant now = Instant.now();
      Map<String, Object> claims = new LinkedHashMap<>();
      claims.put("iss", issuer());
      claims.put("sub", subject);
      claims.put("aud", audience);
      claims.put("groups", groups);
      claims.put("iat", now.getEpochSecond());
      claims.put("exp", now.plus(ttl).getEpochSecond());
      claims.put("jti", UUID.randomUUID().toString());
      claims.putAll(extraClaims);

      return Jws.sign(header, claims, signingKey);
    }
  }
}
