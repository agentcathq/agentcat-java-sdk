package com.agentcat.sdk.modules.events;

import com.agentcat.api.model.PublishEventRequest;

/**
 * Delivers one event to the AgentCat API. The default is {@link HttpEventSender}.
 */
@FunctionalInterface
public interface EventSender {

    /**
     * Sends the event and returns when the API accepted it.
     *
     * @param request the event body
     * @throws EventSendException when the API rejects the event or cannot be reached
     */
    void send(PublishEventRequest request) throws EventSendException;
}
