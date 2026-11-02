package com.agentcat.sdk.modules.events;

import static com.agentcat.sdk.logging.AgentCatLogger.writeToLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validates and filters a raw tag map against AgentCat tag constraints.
 *
 * <p>Invalid entries are silently dropped and logged. The returned map contains
 * only entries that passed all checks, in insertion order. Returns {@code null}
 * if no valid entries remain.
 */
public final class TagValidator {

    private static final Pattern TAG_KEY_PATTERN  = Pattern.compile("[a-zA-Z0-9$_.:\\- ]+");
    private static final int     MAX_TAG_KEY_LEN   = 32;
    private static final int     MAX_TAG_VALUE_LEN = 200;
    private static final int     MAX_TAG_ENTRIES   = 50;

    private TagValidator() {}

    /**
     * Validates {@code raw} and returns a filtered copy, or {@code null} if nothing is valid.
     *
     * @param raw the raw tag map from a customer hook; may be {@code null}
     * @return a valid, bounded tag map, or {@code null}
     */
    public static Map<String, String> validate(Map<String, ?> raw) {
        if (raw == null || raw.isEmpty()) return null;
        List<Map.Entry<String, String>> valid = collectValid(raw);
        if (valid.isEmpty()) return null;
        return truncateToLimit(valid);
    }

    private static List<Map.Entry<String, String>> collectValid(Map<String, ?> raw) {
        List<Map.Entry<String, String>> valid = new ArrayList<>();
        for (Map.Entry<String, ?> entry : raw.entrySet()) {
            String key      = entry.getKey();
            Object rawValue = entry.getValue();

            if (key == null || key.isEmpty() || !TAG_KEY_PATTERN.matcher(key).matches()) {
                writeToLog("Dropping invalid tag: key \"" + key
                        + "\" contains invalid characters or is empty");
                continue;
            }
            if (key.length() > MAX_TAG_KEY_LEN) {
                writeToLog("Dropping invalid tag: key \"" + key
                        + "\" exceeds max length of " + MAX_TAG_KEY_LEN);
                continue;
            }
            if (!(rawValue instanceof String)) {
                writeToLog("Dropping invalid tag: key \"" + key + "\" has non-string value");
                continue;
            }
            String value = (String) rawValue;
            if (value.length() > MAX_TAG_VALUE_LEN) {
                writeToLog("Dropping invalid tag: key \"" + key
                        + "\" value exceeds max length of " + MAX_TAG_VALUE_LEN);
                continue;
            }
            if (value.contains("\n") || value.contains("\r")) {
                writeToLog("Dropping invalid tag: key \"" + key
                        + "\" value contains newline character");
                continue;
            }
            valid.add(Map.entry(key, value));
        }
        return valid;
    }

    private static Map<String, String> truncateToLimit(List<Map.Entry<String, String>> valid) {
        if (valid.size() > MAX_TAG_ENTRIES) {
            int dropped = valid.size() - MAX_TAG_ENTRIES;
            writeToLog("Dropping " + dropped + " tag(s) — exceeds maximum of "
                    + MAX_TAG_ENTRIES + " entries per event");
            valid = valid.subList(0, MAX_TAG_ENTRIES);
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : valid) {
            result.put(e.getKey(), e.getValue());
        }
        return result;
    }
}
