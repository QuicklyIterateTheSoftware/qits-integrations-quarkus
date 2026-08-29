package eu.wohlben.qits.servicemock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The generic core alone: a no-code fake of an arbitrary service is stubs plus recordings — no
 * subclass, no crypto, nothing idp-shaped.
 */
class MockServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void stubbedRoutesAnswerAndEverythingIsRecorded() throws Exception {
    try (MockService projects = MockService.start("qits-projects")) {
      projects.stub(
          "GET", "/projects/api/names/qits/qits-userflows", Map.of("repositoryId", "abc-123"));
      projects.stub("PUT", "/projects/api/tasks/7", 204, Map.of());

      HttpResponse<String> found = get(projects, "/projects/api/names/qits/qits-userflows");
      assertEquals(200, found.statusCode());
      assertEquals("abc-123", MAPPER.readTree(found.body()).get("repositoryId").asText());

      // an unstubbed path answers 404 naming the impersonated service, and is still recorded
      HttpResponse<String> missed = get(projects, "/projects/api/nope");
      assertEquals(404, missed.statusCode());
      assertTrue(missed.body().contains("qits-projects"), missed.body());

      List<String> paths =
          projects.recordedRequests().stream().map(MockService.RecordedRequest::path).toList();
      assertEquals(
          List.of("/projects/api/names/qits/qits-userflows", "/projects/api/nope"), paths);

      projects.reset();
      assertEquals(List.of(), projects.recordedRequests(), "reset clears recordings");
      // the stubs survive a reset
      assertEquals(200, get(projects, "/projects/api/names/qits/qits-userflows").statusCode());
    }
  }

  @Test
  void everyRecordingCarriesTheStatusTheMockAnswered() throws Exception {
    try (MockService ci = MockService.start("qits-ci")) {
      ci.stub("GET", "/ci/api/builds/7", Map.of("state", "SUCCEEDED"));
      ci.stub("GET", "/ci/api/builds/8", 202, Map.of("state", "RUNNING"));

      assertEquals(200, get(ci, "/ci/api/builds/7").statusCode());
      assertEquals(202, get(ci, "/ci/api/builds/8").statusCode());
      // the unstubbed route still answers 404 — and records that it did
      HttpResponse<String> missed = get(ci, "/ci/api/builds/9");
      assertEquals(404, missed.statusCode());
      assertTrue(missed.body().contains("qits-ci"), missed.body());

      assertEquals(
          List.of(200, 202, 404),
          ci.recordedRequests().stream().map(MockService.RecordedRequest::status).toList(),
          "a recording carries what the mock actually answered, not just that it was asked");
    }
  }

  @Test
  void queryStringsAreRecordedVerbatimAndAbsentWhenTheUriCarriedNone() throws Exception {
    try (MockService docs = MockService.start("qits-docs")) {
      docs.stub("GET", "/docs/api/search", Map.of("hits", List.of()));

      assertEquals(200, get(docs, "/docs/api/search?q=a%20b&tag=x+y&flag").statusCode());
      assertEquals(200, get(docs, "/docs/api/search").statusCode());

      List<MockService.RecordedRequest> recorded = docs.recordedRequests();
      assertEquals(
          "q=a%20b&tag=x+y&flag",
          recorded.get(0).query(),
          "the raw query is recorded verbatim — neither decoded nor parsed");
      assertEquals("/docs/api/search", recorded.get(0).path(), "the path stays query-free");
      assertNull(recorded.get(1).query(), "no query string means no query");

      // both sides of the control endpoint agree on absent-vs-present: the key is omitted, not null
      JsonNode array = MAPPER.readTree(get(docs, "/__mock/requests").body());
      assertEquals("q=a%20b&tag=x+y&flag", array.get(0).get("query").asText());
      assertEquals(200, array.get(0).get("status").asInt());
      assertFalse(array.get(1).has("query"), "a query-less request emits no \"query\" key at all");
    }
  }

  @Test
  void restubbingReplacesAndMethodIsPartOfTheRoute() throws Exception {
    try (MockService events = MockService.start("qits-events")) {
      events.stub("GET", "/events/api/streams", Map.of("streams", List.of()));
      events.stub("GET", "/events/api/streams", Map.of("streams", List.of("scm")));

      JsonNode body = MAPPER.readTree(get(events, "/events/api/streams").body());
      assertEquals("scm", body.get("streams").get(0).asText(), "re-stub replaces the route");

      // same path, different method: no stub
      HttpResponse<String> post =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(URI.create(events.baseUrl() + "/events/api/streams"))
                      .POST(HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      assertEquals(404, post.statusCode());
    }
  }

  @Test
  void attachedHandleSharesServerAndRecordingsButCannotStub() throws Exception {
    MockService owned = MockService.ensureStarted("qits-observability");
    owned.stub("GET", "/observability/api/ping", Map.of("ok", true));
    owned.stub("GET", "/observability/api/metrics", 202, Map.of("accepted", true));

    MockService attached = MockService.attach("qits-observability");
    assertEquals(owned.baseUrl(), attached.baseUrl());

    assertEquals(200, get(attached, "/observability/api/ping").statusCode());
    // the recording made through the owned server is visible to the attached handle
    assertTrue(
        attached.recordedRequests().stream()
            .anyMatch(r -> "/observability/api/ping".equals(r.path())));

    // status and query survive the control round-trip an attached handle reads recordings over
    assertEquals(202, get(attached, "/observability/api/metrics?window=1h").statusCode());
    MockService.RecordedRequest metrics = attached.recordedRequests().getLast();
    assertEquals("/observability/api/metrics", metrics.path());
    assertEquals("window=1h", metrics.query());
    assertEquals(202, metrics.status());
    assertNull(
        attached.recordedRequests().stream()
            .filter(r -> "/observability/api/ping".equals(r.path()))
            .findFirst()
            .orElseThrow()
            .query(),
        "the query-less ping reads back as a null query, not an empty one");

    assertThrows(IllegalStateException.class, () -> attached.stub("GET", "/x", Map.of()));
    assertThrows(IllegalStateException.class, () -> MockService.attach("never-started"));
  }

  private static HttpResponse<String> get(MockService mock, String path) throws Exception {
    try (HttpClient client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create(mock.baseUrl() + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
