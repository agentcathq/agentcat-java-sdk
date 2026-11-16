package com.agentcat.sdk.modules.serverinfo;

import com.agentcat.api.model.PublishServerInfoRequest;
import com.agentcat.sdk.modules.events.EventSendException;

/**
 * Delivers one server info heartbeat to the AgentCat API. The default is
 * {@link com.agentcat.sdk.modules.events.HttpEventSender}.
 */
@FunctionalInterface
public interface ServerInfoSender {

    /**
     * Sends the heartbeat once.
     *
     * @return the HTTP status of the answer, whatever it is
     * @throws EventSendException when no answer arrived (connection failure or timeout)
     */
    int sendServerInfo(PublishServerInfoRequest request) throws EventSendException;
}
