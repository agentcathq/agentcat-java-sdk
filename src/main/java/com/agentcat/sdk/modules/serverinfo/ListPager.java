package com.agentcat.sdk.modules.serverinfo;

import reactor.core.publisher.Mono;

/** Reads one page of one of the server's lists. A {@code null} cursor asks for the first page. */
@FunctionalInterface
interface ListPager {

    Mono<ListPage> page(String cursor);
}
