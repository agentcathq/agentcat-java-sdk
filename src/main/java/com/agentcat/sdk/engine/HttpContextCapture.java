package com.agentcat.sdk.engine;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpTransportContextExtractor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import static com.agentcat.sdk.logging.AgentCatLogger.writeToLog;

/**
 * Captures the HTTP request of every MCP call, so that the published event carries all of
 * its headers at {@code parameters.extra.http.req}, like the TypeScript SDK's v2 shape.
 *
 * <p>The MCP Java SDK never hands a tool handler the HTTP request. A handler gets a
 * {@link McpTransportContext} only, and the transport fills that context with its
 * {@code contextExtractor}, which is the one place that sees the raw request. So
 * {@link #install} wraps the transport's extractor: the customer's extractor still runs and
 * its context is unchanged, and the request snapshot rides along under {@link #CONTEXT_KEY}.
 *
 * <p>The request types are reached by reflection through their public interfaces, because
 * the servlet API and Spring are not dependencies of this SDK. Supported: every transport
 * whose extractor takes a {@code jakarta.servlet.http.HttpServletRequest} (the MCP SDK's own
 * {@code HttpServlet*} transports), Spring WebMVC's {@code ServerRequest} and Spring WebFlux's
 * {@code ServerRequest}.
 */
public final class HttpContextCapture {

    /** Transport context key that holds the request snapshot: {@code {method, url, headers}}. */
    public static final String CONTEXT_KEY = "com.agentcat.http.request";

    private static final String EXTRACTOR_FIELD = "contextExtractor";
    private static final String TRANSPORT_FIELD = "mcpTransportProvider";

    private static final String[] SERVLET_REQUEST = {
            "jakarta.servlet.http.HttpServletRequest", "javax.servlet.http.HttpServletRequest"};
    private static final String WEBMVC_REQUEST  = "org.springframework.web.servlet.function.ServerRequest";
    private static final String WEBFLUX_REQUEST = "org.springframework.web.reactive.function.server.ServerRequest";
    private static final String WEBFLUX_HEADERS =
            "org.springframework.web.reactive.function.server.ServerRequest$Headers";
    private static final String SPRING_HEADERS  = "org.springframework.http.HttpHeaders";

    private static final AtomicBoolean PROJECTION_FAILURE_LOGGED = new AtomicBoolean();

    private HttpContextCapture() {}

    // ── Install ────────────────────────────────────────────────────────────────

    /**
     * Wraps the context extractor of the server's transport. Does nothing when the transport
     * has no extractor (stdio), and nothing on a second call. Never throws.
     *
     * @param asyncServer a {@code McpAsyncServer} or {@code McpStatelessAsyncServer}
     */
    @SuppressWarnings("unchecked")
    public static void install(Object asyncServer) {
        try {
            Field transportField = ReflectionUtils.findFieldInHierarchy(asyncServer.getClass(), TRANSPORT_FIELD);
            if (transportField == null) {
                writeToLog("HTTP header capture is off: the server has no '" + TRANSPORT_FIELD + "' field");
                return;
            }
            transportField.setAccessible(true);
            Object transport = transportField.get(asyncServer);
            if (transport == null) return;

            Field extractorField = ReflectionUtils.findFieldInHierarchy(transport.getClass(), EXTRACTOR_FIELD);
            if (extractorField == null
                    || !McpTransportContextExtractor.class.isAssignableFrom(extractorField.getType())) {
                writeToLog("HTTP header capture is off: transport " + transport.getClass().getName()
                        + " has no context extractor");
                return;
            }
            extractorField.setAccessible(true);
            Object current = extractorField.get(transport);
            if (current instanceof CapturingExtractor) return;

            extractorField.set(transport,
                    new CapturingExtractor((McpTransportContextExtractor<Object>) current));
            writeToLog("HTTP header capture installed on " + transport.getClass().getName());
        } catch (Throwable t) {
            writeToLog("Warning: HTTP header capture could not be installed - " + t);
        }
    }

    // ── Read ───────────────────────────────────────────────────────────────────

    /**
     * Returns the request snapshot of a call, or {@code null} when there is none.
     *
     * @param exchangeOrTransport the {@link McpAsyncServerExchange} (stateful) or the
     *                            {@link McpTransportContext} (stateless) of the call
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> read(Object exchangeOrTransport) {
        try {
            McpTransportContext context = null;
            if (exchangeOrTransport instanceof McpAsyncServerExchange exchange) {
                context = exchange.transportContext();
            } else if (exchangeOrTransport instanceof McpTransportContext ctx) {
                context = ctx;
            }
            if (context == null) return null;
            return context.get(CONTEXT_KEY) instanceof Map<?, ?> request
                    ? (Map<String, Object>) request : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ── Extractor and context ──────────────────────────────────────────────────

    /** Runs the customer's extractor, then adds the request snapshot to its context. */
    static final class CapturingExtractor implements McpTransportContextExtractor<Object> {

        private final McpTransportContextExtractor<Object> delegate;

        CapturingExtractor(McpTransportContextExtractor<Object> delegate) {
            this.delegate = delegate;
        }

        @Override
        public McpTransportContext extract(Object request) {
            // The customer's extractor is not ours to guard: its exceptions pass, as before.
            McpTransportContext base = delegate != null ? delegate.extract(request) : McpTransportContext.EMPTY;
            Map<String, Object> snapshot = null;
            try {
                snapshot = project(request);
            } catch (Throwable t) {
                if (PROJECTION_FAILURE_LOGGED.compareAndSet(false, true)) {
                    writeToLog("Warning: HTTP header capture failed for "
                            + (request != null ? request.getClass().getName() : "null") + " - " + t);
                }
            }
            return snapshot == null ? base : new CapturedContext(base, snapshot);
        }
    }

    /** The customer's context plus one more key. Every other key reads through. */
    static final class CapturedContext implements McpTransportContext {

        private final McpTransportContext base;
        private final Map<String, Object> snapshot;

        CapturedContext(McpTransportContext base, Map<String, Object> snapshot) {
            this.base     = base;
            this.snapshot = snapshot;
        }

        @Override
        public Object get(String key) {
            if (CONTEXT_KEY.equals(key)) return snapshot;
            return base != null ? base.get(key) : null;
        }
    }

    // ── Projection ─────────────────────────────────────────────────────────────

    /**
     * Copies the request into plain data: {@code {method, url, headers}}. Header names are
     * lower case, and the values of a repeated header are joined with {@code ", "}, like the
     * Fetch API's {@code Headers}. The copy holds no reference to the request, because
     * servers recycle request objects and the event is published later, on another thread.
     *
     * @return the snapshot, or {@code null} for a request type that is not supported
     */
    static Map<String, Object> project(Object request) throws Exception {
        if (request == null) return null;

        for (String name : SERVLET_REQUEST) {
            Class<?> servlet = findType(request.getClass(), name);
            if (servlet != null) return projectServlet(servlet, request);
        }

        Class<?> webMvc = findType(request.getClass(), WEBMVC_REQUEST);
        if (webMvc != null) {
            return project(webMvc.getMethod("servletRequest").invoke(request));
        }

        Class<?> webFlux = findType(request.getClass(), WEBFLUX_REQUEST);
        if (webFlux != null) return projectWebFlux(webFlux, request);

        return null;
    }

    private static Map<String, Object> projectServlet(Class<?> servlet, Object request) throws Exception {
        Method getHeaders = servlet.getMethod("getHeaders", String.class);
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<?> names = (Enumeration<?>) servlet.getMethod("getHeaderNames").invoke(request);
        while (names != null && names.hasMoreElements()) {
            String name = String.valueOf(names.nextElement());
            StringJoiner joined = new StringJoiner(", ");
            Enumeration<?> values = (Enumeration<?>) getHeaders.invoke(request, name);
            while (values != null && values.hasMoreElements()) {
                joined.add(String.valueOf(values.nextElement()));
            }
            putHeader(headers, name, joined.toString());
        }

        Object url   = servlet.getMethod("getRequestURL").invoke(request);
        Object query = servlet.getMethod("getQueryString").invoke(request);
        return snapshot(
                servlet.getMethod("getMethod").invoke(request),
                url == null ? null : (query == null ? url.toString() : url + "?" + query),
                headers);
    }

    private static Map<String, Object> projectWebFlux(Class<?> webFlux, Object request) throws Exception {
        Object requestHeaders = webFlux.getMethod("headers").invoke(request);
        Object httpHeaders = Class.forName(WEBFLUX_HEADERS, false, webFlux.getClassLoader())
                .getMethod("asHttpHeaders").invoke(requestHeaders);

        // HttpHeaders is a Map in Spring 6 and its own type in Spring 7: both have forEach.
        Map<String, String> headers = new LinkedHashMap<>();
        Class<?> headersType = findType(httpHeaders.getClass(), SPRING_HEADERS);
        BiConsumer<Object, Object> collect = (name, values) -> putHeader(headers, String.valueOf(name),
                values instanceof List<?> list
                        ? String.join(", ", list.stream().map(String::valueOf).toList())
                        : String.valueOf(values));
        headersType.getMethod("forEach", BiConsumer.class).invoke(httpHeaders, collect);

        return snapshot(
                webFlux.getMethod("method").invoke(request),
                webFlux.getMethod("uri").invoke(request),
                headers);
    }

    private static void putHeader(Map<String, String> headers, String name, String value) {
        headers.merge(name.toLowerCase(Locale.ROOT), value, (first, next) -> first + ", " + next);
    }

    private static Map<String, Object> snapshot(Object method, Object url, Map<String, String> headers) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (method != null) snapshot.put("method", method.toString());
        if (url != null)    snapshot.put("url", url.toString());
        snapshot.put("headers", Collections.unmodifiableMap(headers));
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Finds the class or interface called {@code name} among the supertypes of {@code type}.
     * A lookup by name through the object's own hierarchy needs no class loader guess, and
     * its methods are callable even when the implementation class is not public.
     */
    private static Class<?> findType(Class<?> type, String name) {
        if (type == null) return null;
        if (type.getName().equals(name)) return type;
        for (Class<?> iface : type.getInterfaces()) {
            Class<?> found = findType(iface, name);
            if (found != null) return found;
        }
        return findType(type.getSuperclass(), name);
    }
}
