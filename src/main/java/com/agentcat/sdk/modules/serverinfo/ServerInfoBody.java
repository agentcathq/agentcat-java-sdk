package com.agentcat.sdk.modules.serverinfo;

import com.agentcat.api.model.PublishServerInfoRequest;
import com.agentcat.sdk.tools.GetMoreTools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.agentcat.sdk.logging.AgentCatLogger.writeToLog;

/** Builds the body of the heartbeat from what the server shows an agent. */
final class ServerInfoBody {

    static final String TOOLS = "tools";

    private ServerInfoBody() {}

    /**
     * Reads every list that the server has a handler for. A failed list other than the tool
     * list is left out.
     *
     * @param deadlineNanos the {@link System#nanoTime()} at which the whole build stops
     * @throws ListReader.ListFailedException when the tool list fails
     */
    static Map<String, Object> features(ServerFeatures server, long deadlineNanos)
            throws ListReader.ListFailedException {
        Map<String, Object> features = new LinkedHashMap<>();
        for (Map.Entry<String, ListPager> list : server.lists().entrySet()) {
            String key = list.getKey();
            long listDeadline = Math.min(deadlineNanos, System.nanoTime() + ListReader.LIST_LIMIT_NANOS);
            try {
                List<Map<String, Object>> items = ListReader.readAll(list.getValue(), listDeadline);
                features.put(key, TOOLS.equals(key) ? withOneGetMoreTools(items) : items);
            } catch (ListReader.ListFailedException e) {
                if (TOOLS.equals(key)) throw e;
                writeToLog("server info: the " + key + " list failed, sending without it: " + e.getMessage());
            }
        }
        return features;
    }

    private static List<Map<String, Object>> withOneGetMoreTools(List<Map<String, Object>> tools) {
        List<Map<String, Object>> out = new ArrayList<>(tools.size());
        boolean seen = false;
        for (Map<String, Object> tool : tools) {
            if (GetMoreTools.GET_MORE_TOOLS_NAME.equals(tool.get("name"))) {
                if (seen) continue;
                seen = true;
            }
            out.add(tool);
        }
        return out;
    }

    static PublishServerInfoRequest request(String projectId, String serverName, String serverVersion,
                                            String title, String instructions, Map<String, Object> features) {
        return new PublishServerInfoRequest()
                .projectId(projectId)
                .serverName(serverName)
                .serverVersion(serverVersion)
                .serverTitle(title)
                .instructions(instructions)
                .features(features);
    }

    /** A copy of {@code features} whose tools have no {@code inputSchema} and no {@code outputSchema}. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> withoutSchemas(Map<String, Object> features) {
        Map<String, Object> out = new LinkedHashMap<>(features);
        if (out.get(TOOLS) instanceof List<?> tools) {
            List<Object> slim = new ArrayList<>(tools.size());
            for (Object tool : tools) {
                if (tool instanceof Map<?, ?> map) {
                    Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) map);
                    copy.remove("inputSchema");
                    copy.remove("outputSchema");
                    slim.add(copy);
                } else {
                    slim.add(tool);
                }
            }
            out.put(TOOLS, slim);
        }
        return out;
    }
}
