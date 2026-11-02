package com.agentcat.sdk.e2e;

import com.agentcat.sdk.e2e.lanes.StatefulSyncLane;
import com.agentcat.sdk.integration.EventCapture;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

/**
 * E2E test matrix: every scenario × every lane, driven over a real embedded
 * HTTP server with no in-process shortcuts.
 *
 * <h3>Structure</h3>
 * <ul>
 *   <li>{@link E2ELane} implementations spin up an embedded Jetty, apply
 *       {@code AgentCat.track()}, and return a {@link LaneInstance} that
 *       vends fresh {@link McpSyncClient}s over real HTTP.</li>
 *   <li>{@link E2EToolkit} registers five standard tools ({@code echo},
 *       {@code loose}, {@code strict}, {@code structured}, {@code boom})
 *       on the server and records handler invocations in a {@link ToolSink}
 *       for strip-proof assertions.</li>
 *   <li>{@link ScenarioTypes#SCENARIOS} contains the 20 scenario definitions,
 *       ported from the TypeScript {@code v1-matrix.test.ts} scenario array.</li>
 * </ul>
 *
 * <h3>Adding new lanes</h3>
 * Add an {@link E2ELane} implementation to {@code lanes/} and include it in
 * {@link #lanesAndScenarios()}.
 */
class E2EMatrixTest {

    private EventCapture capture;

    @BeforeEach
    void setUpCapture() throws Exception {
        capture = new EventCapture();
        capture.start();
    }

    @AfterEach
    void tearDownCapture() throws Exception {
        capture.stop();
    }

    @ParameterizedTest(name = "[{0}] {1}")
    @MethodSource("lanesAndScenarios")
    void e2e(E2ELane lane, E2EScenario scenario) throws Exception {
        ToolSink sink = new ToolSink();
        try (LaneInstance instance = lane.start(scenario.options(), sink)) {
            McpSyncClient client = instance.connectClient();
            ScenarioContext ctx = new ScenarioContext(client, capture, sink, lane);
            scenario.script().run(ctx);
        }
    }

    static Stream<Arguments> lanesAndScenarios() {
        List<E2ELane> lanes = List.of(StatefulSyncLane.INSTANCE);

        return lanes.stream().flatMap(lane ->
                ScenarioTypes.SCENARIOS.stream()
                        .filter(s -> s.lanes() == null || s.lanes().contains(lane.id()))
                        .map(s -> Arguments.of(lane, s)));
    }
}
