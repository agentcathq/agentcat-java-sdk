package com.agentcat.sdk.modules.serverinfo;

import com.agentcat.sdk.modules.pipeline.JsonTree;
import io.modelcontextprotocol.json.McpJsonDefaults;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads every page of one list. The list fails when a cursor repeats, when it has more than
 * {@value #MAX_PAGES} pages, when it takes longer than its time limit, or when the handler fails.
 */
final class ListReader {

    static final int MAX_PAGES = 100;
    static final long LIST_LIMIT_NANOS = Duration.ofSeconds(5).toNanos();

    private ListReader() {}

    /**
     * Returns each item as the JSON object that the library puts on the wire.
     *
     * @param deadlineNanos the {@link System#nanoTime()} at which the list fails
     * @throws ListFailedException when the list fails
     */
    static List<Map<String, Object>> readAll(ListPager pager, long deadlineNanos) throws ListFailedException {
        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        for (int pages = 1; ; pages++) {
            if (pages > MAX_PAGES) throw new ListFailedException("more than " + MAX_PAGES + " pages");
            ListPage page = fetch(pager, cursor, deadlineNanos);
            for (Object item : page.items()) items.add(wireObject(item));
            String next = page.nextCursor();
            if (next == null) return items;
            if (!cursors.add(next)) throw new ListFailedException("a cursor repeated");
            cursor = next;
        }
    }

    private static ListPage fetch(ListPager pager, String cursor, long deadlineNanos) throws ListFailedException {
        long left = deadlineNanos - System.nanoTime();
        if (left <= 0) throw new ListFailedException("time limit passed");
        ListPage page;
        try {
            page = pager.page(cursor).block(Duration.ofNanos(left));
        } catch (Throwable t) {
            throw new ListFailedException("the handler failed or passed the time limit ("
                    + t.getClass().getSimpleName() + ")");
        }
        if (page == null || page.items() == null) throw new ListFailedException("the handler gave no page");
        return page;
    }

    /**
     * Converts with the MCP library's own JSON mapper, so the item is what the library puts
     * on the wire. The result holds only maps, lists, strings, numbers, booleans and nulls.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> wireObject(Object item) throws ListFailedException {
        Object tree;
        try {
            tree = JsonTree.toTree(McpJsonDefaults.getMapper().convertValue(item, Object.class));
        } catch (Throwable t) {
            throw new ListFailedException("an item could not be converted (" + t.getClass().getSimpleName() + ")");
        }
        if (tree instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw new ListFailedException("an item is not a JSON object");
    }

    /** One list could not be read whole. */
    static final class ListFailedException extends Exception {
        private static final long serialVersionUID = 1L;

        ListFailedException(String reason) {
            super(reason);
        }
    }
}
