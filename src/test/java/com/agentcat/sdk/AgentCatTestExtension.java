package com.agentcat.sdk;

import com.agentcat.api.model.PublishServerInfoRequest;
import com.agentcat.sdk.logging.AgentCatLogger;
import com.agentcat.sdk.modules.events.EventQueue;
import com.agentcat.sdk.modules.events.EventSendException;
import com.agentcat.sdk.modules.serverinfo.ServerInfoSender;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * JUnit 5 auto-extension that installs a test-safe configuration before any test class runs.
 *
 * <ul>
 *   <li>Redirects the AgentCat API to {@code http://localhost:1} so that tests which forget to
 *       start {@link com.agentcat.sdk.integration.EventCapture} get an immediate
 *       "connection refused" instead of sending events to {@code api.agentcat.com}.</li>
 *   <li>Replaces the server info sender with {@link ServerInfoRecorder}, which sends nothing.</li>
 *   <li>Redirects {@code agentcat.log} to the system temp directory so that test runs do not
 *       pollute {@code ~/agentcat.log}.</li>
 * </ul>
 *
 * <p>Registered automatically via
 * {@code META-INF/services/org.junit.jupiter.api.extension.Extension} and
 * {@code junit-platform.properties} ({@code junit.jupiter.extensions.autodetection.enabled=true}).
 * No {@code @ExtendWith} annotation is needed in individual test classes.
 */
public class AgentCatTestExtension implements BeforeAllCallback {

    private static final ServerInfoRecorder SERVER_INFO_RECORDER = new ServerInfoRecorder();

    private static volatile boolean initialized = false;

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        if (initialized) return;
        synchronized (AgentCatTestExtension.class) {
            if (initialized) return;
            EventQueue.getInstance().configure("http://localhost:1");
            installServerInfoSender(SERVER_INFO_RECORDER);
            AgentCatLogger.setLogDir(System.getProperty("java.io.tmpdir"));
            initialized = true;
        }
    }

    /** The recorder that receives every server info heartbeat of the test run. */
    public static ServerInfoRecorder serverInfoRecorder() {
        return SERVER_INFO_RECORDER;
    }

    /** Puts {@code sender} in place of the queue's server info sender. */
    public static void installServerInfoSender(ServerInfoSender sender) throws Exception {
        Field field = EventQueue.class.getDeclaredField("serverInfoSender");
        field.setAccessible(true);
        field.set(EventQueue.getInstance(), sender);
    }

    /**
     * Records each server info request and answers with the next scripted status (202 when
     * none is left). It opens no connection.
     */
    public static final class ServerInfoRecorder implements ServerInfoSender {

        private final List<PublishServerInfoRequest> requests = new CopyOnWriteArrayList<>();
        private final ConcurrentLinkedDeque<Integer> statuses = new ConcurrentLinkedDeque<>();

        @Override
        public int sendServerInfo(PublishServerInfoRequest request) throws EventSendException {
            requests.add(request);
            Integer status = statuses.pollFirst();
            if (status == null) return 202;
            if (status < 0) throw new EventSendException(-1, "no answer", null);
            return status;
        }

        /** The answers of the next sends, in order. A negative status means no answer. */
        public void answer(int... next) {
            for (int status : next) statuses.addLast(status);
        }

        public List<PublishServerInfoRequest> requests() {
            return new ArrayList<>(requests);
        }

        public void clear() {
            requests.clear();
            statuses.clear();
        }
    }
}
