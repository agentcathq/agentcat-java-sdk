package com.agentcat.sdk.e2e;

import com.agentcat.sdk.AgentCatOptions;

import java.util.List;

/**
 * A single e2e scenario: AgentCat options + a test script.
 *
 * <p>Java analogue of the TypeScript {@code Scenario} type. The {@code lanes}
 * field restricts the scenario to specific lane IDs; {@code null} means all lanes.
 *
 * @param name    human-readable name, shown in test display output
 * @param options AgentCat options to apply when starting the lane
 * @param lanes   lane IDs this scenario applies to; {@code null} = every lane
 * @param script  the test body; receives a {@link ScenarioContext} and may throw
 */
public record E2EScenario(
        String name,
        AgentCatOptions options,
        List<String> lanes,
        Script script
) {

    /** Convenience constructor: applies to all lanes. */
    public E2EScenario(String name, AgentCatOptions options, Script script) {
        this(name, options, null, script);
    }

    /** Functional interface for scenario scripts that may throw. */
    @FunctionalInterface
    public interface Script {
        void run(ScenarioContext ctx) throws Exception;
    }

    /** Returns the scenario name — used in JUnit parameterised display names. */
    @Override
    public String toString() {
        return name;
    }
}
