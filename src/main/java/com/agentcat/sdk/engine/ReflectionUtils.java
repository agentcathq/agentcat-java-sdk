package com.agentcat.sdk.engine;

import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static com.agentcat.sdk.logging.AgentCatLogger.writeToLog;

/**
 * Reflection helpers for accessing private fields on MCP SDK objects.
 *
 * <p>The MCP Java SDK does not expose its internal {@code tools} list or the
 * {@code asyncServer} reference via public API. These utilities are the only place
 * in the SDK where reflection is used — centralised here so any breakage from
 * a future MCP SDK update is easy to locate and fix.
 */
public final class ReflectionUtils {

    private ReflectionUtils() {}

    /**
     * Reads a private field from {@code obj} by name.
     *
     * @param <T>       expected type of the field value
     * @param obj       the object to read from
     * @param fieldName the declared field name
     * @return the field value cast to {@code T}
     * @throws Exception if the field does not exist or is inaccessible
     */
    @SuppressWarnings("unchecked")
    public static <T> T readField(Object obj, String fieldName) throws Exception {
        Field field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (T) field.get(obj);
    }

    /**
     * Writes a value into a private field of {@code obj} by name.
     *
     * @param obj       the object to modify
     * @param fieldName the declared field name
     * @param value     the new value to set
     * @throws Exception if the field does not exist or is inaccessible
     */
    public static void writeField(Object obj, String fieldName, Object value) throws Exception {
        Field field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }

    /**
     * Walks the class hierarchy (including superclasses) to find a declared field by name.
     * Returns {@code null} if not found.
     */
    public static Field findFieldInHierarchy(Class<?> cls, String name) {
        while (cls != null) {
            try {
                return cls.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            }
        }
        return null;
    }

    /**
     * Ensures {@code asyncServer} has tools capability declared and that the
     * {@code tools/list} / {@code tools/call} request handlers are registered
     * in the session factory's handler map.
     *
     * <p>When a server is built with no tools, the MCP SDK sets
     * {@code serverCapabilities.tools} to {@code null} and does not register
     * the tools handlers. A subsequent {@code addTool()} call then throws
     * "Server must be configured with tool capabilities", and listing tools
     * fails with "Method not found: tools/list". This method patches both issues
     * via reflection so tools can be added after {@code track()} is called.
     */
    @SuppressWarnings({"unchecked", "JavaReflectionMemberAccess"})
    public static void ensureToolCapabilities(McpAsyncServer asyncServer) {
        try {
            boolean wasPatched = patchServerCapabilities(asyncServer);
            if (!wasPatched) return; // server already had tool capabilities — skip handler registration
            Map<String, Object> requestHandlers = findRequestHandlers(asyncServer);
            if (requestHandlers != null) {
                registerToolHandlers(asyncServer, requestHandlers);
            }
        } catch (Throwable t) {
            writeToLog("ensureToolCapabilities failed (non-fatal): "
                    + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    /**
     * Patches {@code serverCapabilities.tools} on {@code asyncServer} when it is absent.
     *
     * @return {@code false} if the server already had tool capabilities (caller should skip
     *         handler registration); {@code true} if the capabilities were patched
     */
    private static boolean patchServerCapabilities(McpAsyncServer asyncServer) throws Exception {
        McpSchema.ServerCapabilities caps = asyncServer.getServerCapabilities();
        if (caps != null && caps.tools() != null) return false;

        McpSchema.ServerCapabilities updated = (caps != null)
                ? caps.mutate().tools(false).build()
                : McpSchema.ServerCapabilities.builder().tools(false).build();

        Field capsField = McpAsyncServer.class.getDeclaredField("serverCapabilities");
        capsField.setAccessible(true);
        capsField.set(asyncServer, updated);
        return true;
    }

    /**
     * Walks {@code asyncServer → mcpTransportProvider → sessionFactory → requestHandlers}
     * and returns the handler map, or {@code null} if any step in the chain is absent.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> findRequestHandlers(McpAsyncServer asyncServer) throws Exception {
        Field transportField = McpAsyncServer.class.getDeclaredField("mcpTransportProvider");
        transportField.setAccessible(true);
        Object transport = transportField.get(asyncServer);

        Field sessionFactoryField = findFieldInHierarchy(transport.getClass(), "sessionFactory");
        if (sessionFactoryField == null) return null;

        sessionFactoryField.setAccessible(true);
        Object factory = sessionFactoryField.get(transport);
        if (factory == null) return null;

        Field handlersField = findFieldInHierarchy(factory.getClass(), "requestHandlers");
        if (handlersField == null) return null;

        handlersField.setAccessible(true);
        return (Map<String, Object>) handlersField.get(factory);
    }

    /**
     * Registers {@code tools/list} and {@code tools/call} handlers in {@code requestHandlers}.
     * No-op if {@code tools/list} is already present (protects against double-registration).
     */
    private static void registerToolHandlers(
            McpAsyncServer asyncServer,
            Map<String, Object> requestHandlers) throws Exception {
        if (requestHandlers.containsKey("tools/list")) return;

        Method listHandlerMethod = McpAsyncServer.class.getDeclaredMethod("toolsListRequestHandler");
        listHandlerMethod.setAccessible(true);
        Object listHandler = listHandlerMethod.invoke(asyncServer);

        Method callHandlerMethod = McpAsyncServer.class.getDeclaredMethod("toolsCallRequestHandler");
        callHandlerMethod.setAccessible(true);
        Object callHandler = callHandlerMethod.invoke(asyncServer);

        requestHandlers.put("tools/list", listHandler);
        requestHandlers.put("tools/call", callHandler);
    }

    /**
     * Extracts the MCP client identity from a stateful server exchange via reflection.
     *
     * <p>Traverses {@code McpAsyncServerExchange → session → clientInfo}, handling both
     * SDK versions that wrap the value in an {@link AtomicReference} and versions that
     * store it directly. Returns {@code null} on any failure.
     *
     * @param exchange the server exchange from the current tool call
     * @param toolName used only for diagnostic logging on failure
     */
    public static McpSchema.Implementation extractClientInfo(
            McpAsyncServerExchange exchange, String toolName) {
        try {
            Field sessionField = McpAsyncServerExchange.class.getDeclaredField("session");
            sessionField.setAccessible(true);
            Object session = sessionField.get(exchange);
            if (session == null) return null;

            Field ciField = findFieldInHierarchy(session.getClass(), "clientInfo");
            if (ciField == null) return null;
            ciField.setAccessible(true);
            Object ciRef = ciField.get(session);

            if (ciRef instanceof AtomicReference<?> ref) {
                Object val = ref.get();
                if (val instanceof McpSchema.Implementation impl) return impl;
            }
            // Some SDK versions store it directly (non-AtomicReference)
            if (ciRef instanceof McpSchema.Implementation impl) return impl;
        } catch (Throwable t) {
            writeToLog("[client] extractClientInfo failed tool=" + toolName
                    + ": " + t.getMessage());
        }
        return null;
    }
}
