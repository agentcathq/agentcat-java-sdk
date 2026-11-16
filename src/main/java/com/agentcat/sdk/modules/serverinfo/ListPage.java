package com.agentcat.sdk.modules.serverinfo;

import java.util.List;

/** One page of a list: its items as the library returns them, and the cursor of the next page. */
record ListPage(List<?> items, String nextCursor) {}
