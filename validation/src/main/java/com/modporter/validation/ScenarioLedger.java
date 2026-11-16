package com.modporter.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ScenarioLedger {
    private final List<String> requested;
    private final List<String> observed = new ArrayList<>();
    private final List<Map<String, Object>> scenarios = new ArrayList<>();
    private String activeId;
    private long activeStartedAtTick;
    private int failedCount;
    private int cleanupFailureCount;

    ScenarioLedger(List<String> requested) {
        this.requested = List.copyOf(requested);
    }

    void start(String id, long tick) {
        if (activeId != null) {
            throw new IllegalStateException(
                "Scenario already active: " + activeId);
        }
        int nextIndex = observed.size();
        if (nextIndex >= requested.size() ||
                !requested.get(nextIndex).equals(id)) {
            throw new IllegalStateException(
                "Scenario order mismatch; requested=" + requested
                    + ", observed=" + observed + ", next=" + id);
        }
        activeId = id;
        activeStartedAtTick = tick;
    }

    void pass(
        long tick,
        boolean cleanupPassed,
        List<Map<String, Object>> artifacts,
        Map<String, Object> details
    ) {
        finish(tick, true, cleanupPassed, "", artifacts, details);
    }

    void fail(
        long tick,
        boolean cleanupPassed,
        String failure,
        List<Map<String, Object>> artifacts,
        Map<String, Object> details
    ) {
        finish(tick, false, cleanupPassed, failure, artifacts, details);
    }

    String activeId() {
        return activeId;
    }

    void startNext(long tick) {
        if (observed.size() >= requested.size()) {
            throw new IllegalStateException(
                "No requested scenario remains to start");
        }
        start(requested.get(observed.size()), tick);
    }

    Map<String, Object> receipt(
        ValidationConfig config,
        int worldLoadCount,
        ExactJarVerifier.Evidence target,
        Map<String, Object> extra
    ) {
        List<Map<String, Object>> renderedScenarios =
            new ArrayList<>(scenarios);
        for (int index = observed.size(); index < requested.size(); index++) {
            Map<String, Object> unexecuted = new LinkedHashMap<>();
            unexecuted.put("id", requested.get(index));
            unexecuted.put("passed", false);
            unexecuted.put("unexecuted", true);
            unexecuted.put("cleanupPassed", false);
            unexecuted.put("startedAtTick", -1);
            unexecuted.put("finishedAtTick", -1);
            unexecuted.put("artifacts", List.of());
            renderedScenarios.add(unexecuted);
        }

        boolean allPassed = failedCount == 0 &&
            cleanupFailureCount == 0 &&
            observed.equals(requested);
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("schemaVersion", 1);
        receipt.put("lane", config.lane());
        receipt.put("sessionId", config.sessionId());
        receipt.put("processId", ProcessHandle.current().pid());
        receipt.put("processStartCount", 1);
        receipt.put("worldLoadCount", worldLoadCount);
        receipt.put("scenarioCount", requested.size());
        receipt.put("failedCount", failedCount);
        receipt.put("cleanupFailureCount", cleanupFailureCount);
        receipt.put("allScenariosPassed", allPassed);
        receipt.put("requestedScenarios", requested);
        receipt.put("observedScenarios", List.copyOf(observed));
        receipt.put("scenarios", renderedScenarios);
        receipt.put("target", target == null ? null : target.asMap());
        receipt.put("runtimeDirectory",
            config.runtimeDirectory().toString());
        receipt.putAll(extra);
        receipt.put("sealedAtUtc", ReceiptSupport.now());
        return receipt;
    }

    private void finish(
        long tick,
        boolean passed,
        boolean cleanupPassed,
        String failure,
        List<Map<String, Object>> artifacts,
        Map<String, Object> details
    ) {
        if (activeId == null) {
            throw new IllegalStateException("No scenario is active");
        }
        Map<String, Object> scenario = new LinkedHashMap<>();
        scenario.put("id", activeId);
        scenario.put("passed", passed);
        scenario.put("unexecuted", false);
        scenario.put("cleanupPassed", cleanupPassed);
        scenario.put("startedAtTick", activeStartedAtTick);
        scenario.put("finishedAtTick", tick);
        scenario.put("artifacts", List.copyOf(artifacts));
        scenario.put("details", new LinkedHashMap<>(details));
        if (!failure.isBlank()) {
            scenario.put("failure", failure);
        }
        scenarios.add(scenario);
        observed.add(activeId);
        if (!passed) {
            failedCount++;
        }
        if (!cleanupPassed) {
            cleanupFailureCount++;
        }
        activeId = null;
    }
}

