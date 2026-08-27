package eu.wohlben.qits.servicemock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A recording mock of one platform service — the generic far end of a cross-service integration
 * test. A test (usually a userflow) starts one per service it fakes, {@link #stub}s the routes the
 * consumer under test will call, and afterwards asserts the interaction on <b>both ends</b>: the
 * consumer acted on the response, and {@link #recordedRequests()} proves this side served it.
 *
 * <p>Most fakes need no code at all beyond this class: a canned JSON body per route. Every request
 * is recorded — including ones no stub matches, which answer 404 — because "the consumer called
 * the wrong path" is as assertable as the happy path. A service whose behavior canned JSON cannot
 * fake (the idp's key crypto) gets a thin specialization in a subpackage; see {@code idp.MockIdp}.
 *
 * <p>Two ways in:
 *
 * <ul>
 *   <li>{@link #start(String)} — own an instance: fresh port, {@link #close()} stops it.
 *   <li>{@link #ensureStarted(String)} / {@link #attach(String)} — the {@code QuarkusTestProfile}
 *       pattern. A test profile is instantiated in more than one classloader, so a static field
 *       written by one copy is not the field another reads, while the JVM has exactly one property
 *       table: {@code ensureStarted} starts once per JVM per name and parks the port in a system
 *       property; {@code attach} — from any classloader — rebuilds a handle from it. Recordings
 *       need no parking: every handle reads them over the mock's own {@code /__mock/requests}
 *       control endpoint (excluded from recording), so all see the same live list.
 * </ul>
 */
public final class MockService implements AutoCloseable {

  private static final String PORT_PROPERTY_PREFIX = "qits.servicemock.";
  private static final String CONTROL_PATH = "/__mock/requests";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String name;
  private final HttpServer server; // null for an attached handle
  private final int port;
  private final List<RecordedRequest> recorded = new CopyOnWriteArrayList<>();
  private final Map<String, StubbedResponse> stubs = new LinkedHashMap<>();

  private MockService(String name, HttpServer server, int port) {
    this.name = name;
    this.server = server;
    this.port = port;
  }

  /**
   * Start an owned instance on a random port. {@code name} is the service being impersonated
   * (e.g. {@code "qits-platform-idp"}) — it keys the parked port property and names the mock in
   * failures.
   */
  public static MockService start(String name) {
    HttpServer server;
    try {
      server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to start mock of " + name, e);
    }
    MockService mock = new MockService(name, server, server.getAddress().getPort());
    server.createContext("/", mock::handle);
    server.start();
    return mock;
  }

  /**
   * Start once per JVM per {@code name} and park the port in a system property, so any later
   * {@link #attach(String)} — including from another classloader's copy of this class — reaches
   * the same server. The instance lives until the JVM exits.
   */
  public static synchronized MockService ensureStarted(String name) {
    if (System.getProperty(portProperty(name)) != null) {
      return attach(name);
    }
    MockService mock = start(name);
    System.setProperty(portProperty(name), String.valueOf(mock.port));
    return mock;
  }

  /** Rebuild a handle onto the {@link #ensureStarted(String)} instance from the parked port. */
  public static MockService attach(String name) {
    String port = System.getProperty(portProperty(name));
    if (port == null) {
      throw new IllegalStateException(
          "no mock of " + name + " started in this JVM (ensureStarted(\"" + name + "\") first)");
    }
    return new MockService(name, null, Integer.parseInt(port));
  }

  /** The service this mock impersonates. */
  public String name() {
    return name;
  }

  /** The mock's base URL, e.g. {@code http://localhost:49213} — routes are relative to it. */
  public String baseUrl() {
    return "http://localhost:" + port;
  }

  /** Stub {@code method path} to answer 200 with {@code body} serialized as JSON. */
  public MockService stub(String method, String path, Object body) {
    return stub(method, path, 200, body);
  }

  /**
   * Stub {@code method path} to answer {@code status} with {@code body} serialized as JSON. Only
   * the owning instance may stub; an attached handle throws. Re-stubbing a route replaces it.
   */
  public MockService stub(String method, String path, int status, Object body) {
    if (server == null) {
      throw new IllegalStateException(
          "stubs can only be registered on the owning instance of " + name);
    }
    try {
      synchronized (stubs) {
        stubs.put(routeKey(method, path), new StubbedResponse(status, MAPPER.writeValueAsBytes(body)));
      }
    } catch (IOException e) {
      throw new UncheckedIOException("unserializable stub body for " + method + " " + path, e);
    }
    return this;
  }

  /** Every request the mock answered (control traffic excluded), in arrival order. */
  public List<RecordedRequest> recordedRequests() {
    JsonNode array = control("GET");
    List<RecordedRequest> requests = new ArrayList<>();
    for (JsonNode node : array) {
      Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
      node.get("headers")
          .properties()
          .forEach(entry -> headers.put(entry.getKey(), entry.getValue().asText()));
      requests.add(
          new RecordedRequest(
              node.get("method").asText(),
              node.get("path").asText(),
              Instant.parse(node.get("at").asText()),
              headers));
    }
    return requests;
  }

  /** Clear the recordings (the stubs stay). */
  public void reset() {
    control("DELETE");
  }

  /** Stop an owned server; a no-op on an {@link #attach(String)}ed handle. */
  @Override
  public void close() {
    if (server != null) {
      server.stop(0);
    }
  }

  // --- server side ---------------------------------------------------------------------------

  private void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    if (path.startsWith("/__mock")) {
      handleControl(exchange, path);
      return;
    }
    Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    exchange.getRequestHeaders().forEach((header, values) -> headers.put(header, values.getFirst()));
    recorded.add(
        new RecordedRequest(exchange.getRequestMethod(), path, Instant.now(), Map.copyOf(headers)));

    StubbedResponse stub;
    synchronized (stubs) {
      stub = stubs.get(routeKey(exchange.getRequestMethod(), path));
    }
    if (stub != null) {
      respondJson(exchange, stub.status(), stub.body());
    } else {
      respondJson(
          exchange,
          404,
          MAPPER.writeValueAsBytes(
              Map.of("error", "mock of " + name + " has no stub for this route")));
    }
  }

  private void handleControl(HttpExchange exchange, String path) throws IOException {
    if (!CONTROL_PATH.equals(path)) {
      exchange.sendResponseHeaders(404, -1);
      return;
    }
    switch (exchange.getRequestMethod()) {
      case "GET" -> {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RecordedRequest request : recorded) {
          Map<String, Object> node = new LinkedHashMap<>();
          node.put("method", request.method());
          node.put("path", request.path());
          node.put("at", request.at().toString());
          node.put("headers", request.headers());
          out.add(node);
        }
        respondJson(exchange, 200, MAPPER.writeValueAsBytes(out));
      }
      case "DELETE" -> {
        recorded.clear();
        exchange.sendResponseHeaders(204, -1);
      }
      default -> exchange.sendResponseHeaders(405, -1);
    }
  }

  private static void respondJson(HttpExchange exchange, int status, byte[] body)
      throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }

  private JsonNode control(String method) {
    try (HttpClient client = HttpClient.newHttpClient()) {
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(URI.create(baseUrl() + CONTROL_PATH))
                  .method(method, HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      return response.body().isEmpty() ? MAPPER.createArrayNode() : MAPPER.readTree(response.body());
    } catch (IOException e) {
      throw new UncheckedIOException("control call to the mock of " + name + " failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted talking to the mock of " + name, e);
    }
  }

  private static String routeKey(String method, String path) {
    return method + " " + path;
  }

  private static String portProperty(String name) {
    return PORT_PROPERTY_PREFIX + name + ".port";
  }

  /** One request the mock answered: method, path, arrival time, first-value headers. */
  public record RecordedRequest(
      String method, String path, Instant at, Map<String, String> headers) {}

  private record StubbedResponse(int status, byte[] body) {}
}
