package com.gaoyc.studio.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 业务指标：既保留进程内的快照（给 /api/metrics 用），也同步打点到 Micrometer
 * （给 /actuator/prometheus 用，Grafana 直接抓）。
 *
 * <p>为什么要两套：/api/metrics 是给人看的、字段随业务改；Micrometer 那套是给
 * 监控系统用的，指标名要稳定、要有 tag。硬把两者合成一个，最后两边都不好用。
 */
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

    private final Counter generationCounter;
    private final Counter cacheHitCounter;
    private final Counter rateLimitedCounter;
    private final Counter qaBlockedCounter;
    private final Counter failureCounter;
    private final DistributionSummary latencySummary;
    private final DistributionSummary repairSummary;
    private final Counter costMicros;
    private final MeterRegistry registry;

    public StudioMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.generationCounter = Counter.builder("studio.generations")
                .description("完成的生成次数")
                .register(registry);
        this.cacheHitCounter = Counter.builder("studio.cache.hits")
                .description("缓存命中次数")
                .register(registry);
        this.rateLimitedCounter = Counter.builder("studio.rate.limited")
                .description("被限流拒绝的请求数")
                .register(registry);
        this.qaBlockedCounter = Counter.builder("studio.qa.blocked")
                .description("质检未通过就发布的次数")
                .register(registry);
        this.failureCounter = Counter.builder("studio.failures")
                .description("生成过程抛错的次数")
                .register(registry);
        this.latencySummary = DistributionSummary.builder("studio.generation.latency")
                .description("单次生成耗时（毫秒）")
                .baseUnit("milliseconds")
                .register(registry);
        this.repairSummary = DistributionSummary.builder("studio.generation.repair.rounds")
                .description("单次生成需要的修复轮次")
                .register(registry);
        this.costMicros = Counter.builder("studio.generation.cost_micros")
                .description("累计估算成本（微元）")
                .register(registry);
    }

    public void recordGeneration(long latencyMs, double costCny) {
        generations.increment();
        totalLatencyMs.add(latencyMs);
        totalCostCny.add(costCny);
        generationCounter.increment();
        latencySummary.record(latencyMs);
        costMicros.increment(Math.max(0, costCny * 1_000_000));
    }

    public void recordCacheHit() {
        cacheHits.increment();
        cacheHitCounter.increment();
    }

    public void recordRateLimited() {
        rateLimited.increment();
        rateLimitedCounter.increment();
    }

    public void recordRepairs(int rounds) {
        repairRounds.add(rounds);
        repairSummary.record(rounds);
    }

    public void recordQaBlocked() {
        qaBlocked.increment();
        qaBlockedCounter.increment();
    }

    public void recordFailure() {
        failures.increment();
        failureCounter.increment();
    }

    /** 记录本次产物由哪个后端产出（template / llm:xxx），用于两种方案的效果对比。 */
    public void recordBackend(String backend) {
        Counter.builder("studio.generation.backend")
                .description("产物生成后端使用次数")
                .tag("backend", backend == null ? "unknown" : backend)
                .register(registry)
                .increment();
    }

    public void recordIssueCodes(Iterable<String> codes) {
        for (String code : codes) {
            issueCodes.computeIfAbsent(code, ignored -> new LongAdder()).increment();
            Counter.builder("studio.qa.issues")
                    .description("质检发现的缺陷码分布")
                    .tag("code", code)
                    .register(registry)
                    .increment();
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
