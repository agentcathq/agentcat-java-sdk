package com.agentcat.sdk.modules.serverinfo;

import com.agentcat.sdk.AgentCatOptions;
import com.agentcat.sdk.AgentCatTestExtension;
import com.agentcat.sdk.logging.AgentCatLogger;
import com.agentcat.sdk.modules.events.EventQueue;
import com.agentcat.sdk.modules.events.EventSendException;
import com.agentcat.sdk.modules.serverinfo.ServerInfoFixture.Kind;
import com.agentcat.sdk.modules.serverinfo.ServerInfoFixture.Tracked;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A failing, hanging or slow part of the heartbeat never changes or delays a tool call, never
 * leaves an uncaught error, and never writes to standard output or standard error.
 */
class ServerInfoNeverInterruptTest extends HeartbeatTestSupport {

    private static final long NO_WAIT_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final List<Throwable> uncaught = new CopyOnWriteArrayList<>();
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private PrintStream stdout;
    private PrintStream stderr;
    private Thread.UncaughtExceptionHandler previousHandler;

    @BeforeEach
    void watchProcess() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> uncaught.add(error));
        stdout = System.out;
        stderr = System.err;
        PrintStream capture = new PrintStream(output, true);
        System.setOut(capture);
        System.setErr(capture);
    }

    @AfterEach
    void checkProcess() throws Exception {
        awaitIdle();
        System.setOut(stdout);
        System.setErr(stderr);
        Thread.setDefaultUncaughtExceptionHandler(previousHandler);
        assertTrue(uncaught.isEmpty(), "uncaught: " + uncaught);
        assertEquals("", output.toString(), "the heartbeat wrote to standard output or standard error");
    }

    private static boolean normal(McpSchema.CallToolResult result) {
        return result.content().stream().anyMatch(c -> c instanceof McpSchema.TextContent t && "ok".equals(t.text()));
    }

    private static void assertNoWait(long startNanos) {
        long took = System.nanoTime() - startNanos;
        assertTrue(took < NO_WAIT_NANOS, "the tool call waited " + TimeUnit.NANOSECONDS.toMillis(took) + " ms");
    }

    @Test
    void v10_anApiThatNeverAnswersDoesNotDelayTheToolCall() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AgentCatTestExtension.installServerInfoSender(request -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                throw new EventSendException(-1, "interrupted", e);
            }
            return 202;
        });
        try {
            Tracked server = ServerInfoFixture.tracked(Kind.ASYNC);
            long start = System.nanoTime();

            assertTrue(normal(server.call("alpha")));

            assertNoWait(start);
        } finally {
            release.countDown();
        }
    }

    @Test
    void v10_aFailingListHandlerCostsOnlyTheHeartbeat() throws Exception {
        ServerInfoTarget target = target(source(lists("tools", failing(), "prompts", failing())));
        long start = System.nanoTime();

        ServerInfoHeartbeat.onToolCall(target, "alpha");

        assertNoWait(start);
        assertTrue(postsAfterIdle().isEmpty());
    }

    @Test
    void v10_aListHandlerThatNeverReturnsCostsOnlyTheHeartbeat() throws Exception {
        ServerInfoTarget target = target(source(lists("tools", (ListPager) cursor -> Mono.never())));
        long start = System.nanoTime();

        ServerInfoHeartbeat.onToolCall(target, "alpha");

        assertNoWait(start);
        assertTrue(postsAfterIdle().isEmpty());
    }

    @Test
    void v10_aSourceThatThrowsCostsOnlyTheHeartbeat() throws Exception {
        ServerInfoTarget target = target(() -> {
            throw new OutOfMemoryError("simulated");
        });

        ServerInfoHeartbeat.onToolCall(target, "alpha");

        assertTrue(postsAfterIdle().isEmpty());
    }

    @Test
    void v10_aFailingRedactionFunctionDoesNotChangeTheToolCall() throws Exception {
        AgentCatOptions opts = new AgentCatOptions().setRedactSensitiveInformation(text -> {
            throw new IllegalStateException("redaction broke");
        });
        Tracked server = ServerInfoFixture.tracked(Kind.STATELESS_ASYNC, ServerInfoFixture.PROJECT_ID,
                "demo-server", opts, false);
        long start = System.nanoTime();

        assertTrue(normal(server.call("alpha")));

        assertNoWait(start);
        assertTrue(postsAfterIdle().isEmpty());
    }

    @Test
    void v10_anApiThatAnswers500GetsNoSecondPost() throws Exception {
        recorder.answer(500);
        Tracked server = ServerInfoFixture.tracked(Kind.STATELESS_SYNC);
        long start = System.nanoTime();

        assertTrue(normal(server.call("alpha")));

        assertNoWait(start);
        assertEquals(1, postsAfterIdle().size());
    }

    @Test
    void v10_anApiThatGivesNoAnswerGetsNoSecondPost() throws Exception {
        recorder.answer(-1);
        Tracked server = ServerInfoFixture.tracked(Kind.SYNC);

        assertTrue(normal(server.call("alpha")));

        assertEquals(1, postsAfterIdle().size());
    }

    @Test
    void v10_aSenderThatThrowsAnErrorCostsOnlyTheHeartbeat() throws Exception {
        AgentCatTestExtension.installServerInfoSender(request -> {
            throw new LinkageError("simulated");
        });
        Tracked server = ServerInfoFixture.tracked(Kind.SYNC);

        assertTrue(normal(server.call("alpha")));
    }

    @Test
    void theHeartbeatRunsOnADaemonThreadOfTheSdk() throws Exception {
        List<Thread> threads = new CopyOnWriteArrayList<>();
        AgentCatTestExtension.installServerInfoSender(request -> {
            threads.add(Thread.currentThread());
            return 202;
        });

        ServerInfoFixture.tracked(Kind.SYNC).call("alpha");
        awaitIdle();

        assertEquals(1, threads.size());
        assertTrue(threads.get(0).isDaemon());
        assertEquals(ServerInfoHeartbeat.THREAD_NAME, threads.get(0).getName());
    }

    @Test
    void v10_anUnwritableLogWritesNothingToStandardError() throws Exception {
        EventQueue.getInstance().resetForTesting(10_000);
        Path missing = Path.of(System.getProperty("java.io.tmpdir"), "agentcat-no-such-dir-" + UUID.randomUUID(), "log");
        AgentCatLogger.setLogDir(missing.toString());
        try {
            ServerInfoHeartbeat.onToolCall(target(source(lists("tools", failing()))), "alpha");
            awaitIdle();
            ServerInfoHeartbeat.onToolCall(new ServerInfoTarget(ServerInfoFixture.PROJECT_ID, "second-server",
                    "1.0.0", new AgentCatOptions(), source(lists(
                            "tools", pages(List.of(item("alpha"))), "prompts", failing()))), "alpha");
            awaitIdle();
            assertEquals("", McpServerSource.readInstructions(new Object()));

            assertEquals(1, recorder.requests().size());
            assertFalse(Files.exists(missing));
        } finally {
            AgentCatLogger.setLogDir(System.getProperty("java.io.tmpdir"));
        }
    }
}
