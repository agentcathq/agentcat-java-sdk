package com.agentcat.sdk.modules.serverinfo;

import com.agentcat.sdk.AgentCatTestExtension;
import com.agentcat.sdk.modules.events.EventQueue;
import com.agentcat.sdk.modules.events.HttpEventSender;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class ServerInfoTestSetupTest {

    @Test
    void v16_theTestSetupReplacesTheServerInfoSenderWithARecorder() {
        assertSame(AgentCatTestExtension.serverInfoRecorder(), EventQueue.getInstance().serverInfoSender(),
                "every test must send server info to the recorder, never to the API");
    }

    @Test
    void v16_noSenderOfTheTestSetupPointsAtTheRealApi() throws Exception {
        Field field = EventQueue.class.getDeclaredField("sender");
        field.setAccessible(true);
        Object eventSender = field.get(EventQueue.getInstance());
        if (!(eventSender instanceof HttpEventSender)) return;
        Field endpoint = eventSender.getClass().getDeclaredField("endpoint");
        endpoint.setAccessible(true);
        assertFalse(String.valueOf(endpoint.get(eventSender)).contains("api.agentcat.com"));
    }
}
