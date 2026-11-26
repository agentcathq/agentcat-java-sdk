package com.agentcat.sdk.engine;

import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.reactive.function.server.MockServerRequest;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Unit tests of {@link HttpContextCapture}: one per supported request type, plus the fault rules. */
class HttpContextCaptureTest {

    private static MockHttpServletRequest servletRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.setQueryString("run=1");
        request.addHeader("User-Agent", "claude-code/2.1.276 (cli)");
        request.addHeader("Authorization", "Bearer token");
        request.addHeader("X-Forwarded-For", "10.0.0.1");
        request.addHeader("X-Forwarded-For", "10.0.0.2");
        return request;
    }

    private static void assertSnapshot(Map<String, Object> snapshot, String url) {
        assertEquals("POST", snapshot.get("method"));
        assertEquals(url, snapshot.get("url"));
        assertEquals(Map.of(
                "user-agent", "claude-code/2.1.276 (cli)",
                "authorization", "Bearer token",
                "x-forwarded-for", "10.0.0.1, 10.0.0.2"), snapshot.get("headers"));
    }

    @Test
    void projectsServletRequest() throws Exception {
        assertSnapshot(HttpContextCapture.project(servletRequest()), "http://localhost/mcp?run=1");
    }

    @Test
    void projectsSpringWebMvcRequest() throws Exception {
        var request = org.springframework.web.servlet.function.ServerRequest.create(servletRequest(), List.of());
        assertSnapshot(HttpContextCapture.project(request), "http://localhost/mcp?run=1");
    }

    @Test
    void projectsSpringWebFluxRequest() throws Exception {
        var request = MockServerRequest.builder()
                .method(HttpMethod.POST)
                .uri(URI.create("http://localhost/mcp?run=1"))
                .header("User-Agent", "claude-code/2.1.276 (cli)")
                .header("Authorization", "Bearer token")
                .header("X-Forwarded-For", "10.0.0.1")
                .header("X-Forwarded-For", "10.0.0.2")
                .build();
        assertSnapshot(HttpContextCapture.project(request), "http://localhost/mcp?run=1");
    }

    @Test
    void unknownRequestTypeGivesNoSnapshot() throws Exception {
        assertNull(HttpContextCapture.project("not a request"));
        assertNull(HttpContextCapture.project(null));
    }

    @Test
    void unknownRequestTypeKeepsTheCustomerContextUntouched() {
        McpTransportContext customer = McpTransportContext.create(Map.of("k", "v"));
        var extractor = new HttpContextCapture.CapturingExtractor(request -> customer);
        assertSame(customer, extractor.extract("not a request"));
    }

    @Test
    void customerKeysReadThroughAndTheSnapshotIsAdded() {
        var extractor = new HttpContextCapture.CapturingExtractor(
                request -> McpTransportContext.create(Map.of("k", "v")));
        McpTransportContext context = extractor.extract(servletRequest());
        assertEquals("v", context.get("k"));
        assertNull(context.get("missing"));
        assertSnapshot(HttpContextCapture.read(context), "http://localhost/mcp?run=1");
    }

    @Test
    void customerExtractorFailureStillPropagates() {
        var extractor = new HttpContextCapture.CapturingExtractor(request -> {
            throw new IllegalStateException("customer bug");
        });
        assertThrows(IllegalStateException.class, () -> extractor.extract(servletRequest()));
    }

    @Test
    void readGivesNullWithoutASnapshot() {
        assertNull(HttpContextCapture.read(McpTransportContext.EMPTY));
        assertNull(HttpContextCapture.read(null));
        assertNull(HttpContextCapture.read("not a context"));
    }
}
