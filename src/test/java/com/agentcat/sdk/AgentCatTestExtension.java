package com.agentcat.sdk;

import com.agentcat.sdk.logging.AgentCatLogger;
import com.agentcat.sdk.modules.events.EventQueue;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit 5 auto-extension that installs a test-safe configuration before any test class runs.
 *
 * <ul>
 *   <li>Redirects the AgentCat API to {@code http://localhost:1} so that tests which forget to
 *       start {@link com.agentcat.sdk.integration.EventCapture} get an immediate
 *       "connection refused" instead of sending events to {@code api.agentcat.com}.</li>
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

    private static volatile boolean initialized = false;

    @Override
    public void beforeAll(ExtensionContext context) {
        if (initialized) return;
        synchronized (AgentCatTestExtension.class) {
            if (initialized) return;
            EventQueue.getInstance().configure("http://localhost:1");
            AgentCatLogger.setLogDir(System.getProperty("java.io.tmpdir"));
            initialized = true;
        }
    }
}
