package com.agentcat.sdk.modules.serverinfo;

import java.util.Map;

/**
 * What a server shows an agent: its title, its instructions and a pager for each list that it
 * has a handler for, keyed {@code tools}, {@code resources}, {@code resourceTemplates} and
 * {@code prompts}.
 */
record ServerFeatures(String title, String instructions, Map<String, ListPager> lists) {}
