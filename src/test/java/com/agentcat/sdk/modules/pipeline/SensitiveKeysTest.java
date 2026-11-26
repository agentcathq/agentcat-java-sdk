package com.agentcat.sdk.modules.pipeline;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link SensitiveKeys} against the shared vectors, which come from the reference implementation. */
class SensitiveKeysTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> vectors() throws Exception {
        try (InputStream in = SensitiveKeysTest.class.getResourceAsStream("/sensitive-keys-vectors.json")) {
            return JSON.readValue(in, Map.class);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyKeyCase() throws Exception {
        Map<String, Object> vectors = vectors();
        assertEquals(SensitiveKeys.MASK, vectors.get("mask"));
        for (Map<String, Object> c : (List<Map<String, Object>>) vectors.get("keys")) {
            assertEquals(c.get("sensitive"), SensitiveKeys.isSensitiveKey((String) c.get("key")), "key " + c.get("key"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyTreeCase() throws Exception {
        List<Map<String, Object>> trees = (List<Map<String, Object>>) vectors().get("trees");
        List<Map<String, Object>> pristine = (List<Map<String, Object>>) vectors().get("trees");
        for (int i = 0; i < trees.size(); i++) {
            Map<String, Object> c = trees.get(i);
            assertEquals(c.get("expected"), SensitiveKeys.maskSensitiveKeys(c.get("input")), "tree " + c.get("name"));
            assertEquals(pristine.get(i).get("input"), c.get("input"), "input changed: " + c.get("name"));
        }
    }

    @Test
    void stopsWhereTheTruncatorStops() {
        Map<String, Object> cycle = new HashMap<>();
        cycle.put("self", cycle);
        Object deep = "leaf";
        for (int i = 0; i <= Truncator.MAX_DEPTH; i++) deep = Map.of("self", deep);

        assertEquals(SensitiveKeys.MASK, descend(SensitiveKeys.maskSensitiveKeys(deep)));
        assertEquals(SensitiveKeys.MASK, descend(SensitiveKeys.maskSensitiveKeys(cycle)));
    }

    /** The value {@link Truncator#MAX_DEPTH} levels down the "self" chain. */
    private static Object descend(Object tree) {
        for (int i = 0; i < Truncator.MAX_DEPTH; i++) tree = ((Map<?, ?>) tree).get("self");
        return tree;
    }
}
