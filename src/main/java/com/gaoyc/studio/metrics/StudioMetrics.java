package com.gaoyc.studio.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.stereotype.Component;

/** 轻量指标：只统计这个服务真正关心的几个数，不引 Micrometer。 */
@Component
public class StudioMetrics {

    private final LongAdder generations = new LongAdder();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder rateLimited = new LongAdder();
    private final LongAdder repairRounds = new LongAdder();
    private final LongAdder qaBlocked = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final LongAdder totalLatencyMs = new LongAdder();
    private final DoubleAdder totalCostCny = new DoubleAdder();
    private final Map<String, LongAdder> issueCodes = new TreeMap<>();

    public void recordGeneration(long latencyMs, double costCny) {
        generations.increment();
        totalLatencyMs.add(latencyMs);
        totalCostCny.add(costCny);
    }

    public void recordCacheHit() {
        cacheHits.increment();
    }

    public void recordRateLimited() {
        rateLimited.increment();
    }

    public void recordRepairs(int rounds) {
        repairRounds.add(rounds);
    }

    public void recordQaBlocked() {
        qaBlocked.increment();
    }

    public void recordFailure() {
        failures.increment();
    }

    public void recordIssueCodes(Iterable<String> codes) {
        for (String code : codes) {
            issueCodes.computeIfAbsent(code, ignored -> new LongAdder()).increment();
        }
    }

    public Map<String, Object> snapshot() {
        long total = generations.sum();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("generations", total);
        snapshot.put("cacheHits", cacheHits.sum());
        snapshot.put("cacheHitRate", total + cacheHits.sum() == 0
                ? 0.0
                : round((double) cacheHits.sum() / (total + cacheHits.sum())));
        snapshot.put("rateLimited", rateLimited.sum());
        snapshot.put("qaBlocked", qaBlocked.sum());
        snapshot.put("failures", failures.sum());
        snapshot.put("avgRepairRounds", total == 0 ? 0.0 : round((double) repairRounds.sum() / total));
        snapshot.put("avgLatencyMs", total == 0 ? 0L : totalLatencyMs.sum() / total);
        snapshot.put("totalCostCny", Math.round(totalCostCny.sum() * 1_000_000) / 1_000_000.0);
        Map<String, Long> codes = new LinkedHashMap<>();
        issueCodes.forEach((code, adder) -> codes.put(code, adder.sum()));
        snapshot.put("issueCodes", codes);
        return snapshot;
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }
}
