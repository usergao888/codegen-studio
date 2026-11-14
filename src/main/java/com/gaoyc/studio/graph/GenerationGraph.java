package com.gaoyc.studio.graph;

import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.springframework.stereotype.Component;

import com.gaoyc.studio.config.StudioProperties;
import com.gaoyc.studio.domain.AppSpec;
import com.gaoyc.studio.domain.QaIssue;
import com.gaoyc.studio.domain.SiteDraft;
import com.gaoyc.studio.domain.StudioState;
import com.gaoyc.studio.gen.SiteGenerator;
import com.gaoyc.studio.llm.ModelRouter;
import com.gaoyc.studio.publish.SitePublisher;
import com.gaoyc.studio.qa.QaGate;
import com.gaoyc.studio.repair.Repairer;
import com.gaoyc.studio.spec.SpecParser;

/**
 * 六节点生成工作流，用 LangGraph4j 的 {@link StateGraph} 编排。
 *
 * <pre>
 * START → parse → enhance → generate → qa ─┬─(通过)──────────────→ publish → END
 *                                          └─(有问题且未超轮次)→ repair → qa
 * </pre>
 *
 * <p>为什么用图而不是一串 if：质检失败要回到"重新生成"这一步，而且回边必须**有界**；
 * 条件边 + 轮次计数把这条循环表达成结构，比嵌套循环更容易加节点（比如以后插一个"人工确认"）。
 */
@Component
public class GenerationGraph {

    public static final String NODE_PARSE = "parse";
    public static final String NODE_ENHANCE = "enhance";
    public static final String NODE_GENERATE = "generate";
    public static final String NODE_QA = "qa";
    public static final String NODE_REPAIR = "repair";
    public static final String NODE_PUBLISH = "publish";

    public record GraphOutcome(StudioState state, List<String> visitedNodes, long elapsedMs) {
    }

    private final SpecParser specParser;
    private final SiteGenerator generator;
    private final QaGate qaGate;
    private final Repairer repairer;
    private final ModelRouter router;
    private final SitePublisher publisher;
    private final StudioProperties properties;
    private final CompiledGraph<StudioState> compiled;

    public GenerationGraph(SpecParser specParser,
                           SiteGenerator generator,
                           QaGate qaGate,
                           Repairer repairer,
                           ModelRouter router,
                           SitePublisher publisher,
                           StudioProperties properties) throws GraphStateException {
        this.specParser = specParser;
        this.generator = generator;
        this.qaGate = qaGate;
        this.repairer = repairer;
        this.router = router;
        this.publisher = publisher;
        this.properties = properties;
        this.compiled = build();
    }

    // ------------------------------------------------------------------
    private CompiledGraph<StudioState> build() throws GraphStateException {
        StateGraph<StudioState> graph = new StateGraph<>(channels(), StudioState::new);

        graph.addNode(NODE_PARSE, node_async(this::parseNode));
        graph.addNode(NODE_ENHANCE, node_async(this::enhanceNode));
        graph.addNode(NODE_GENERATE, node_async(this::generateNode));
        graph.addNode(NODE_QA, node_async(this::qaNode));
        graph.addNode(NODE_REPAIR, node_async(this::repairNode));
        graph.addNode(NODE_PUBLISH, node_async(this::publishNode));

        graph.addEdge(StateGraph.START, NODE_PARSE);
        graph.addEdge(NODE_PARSE, NODE_ENHANCE);
        graph.addEdge(NODE_ENHANCE, NODE_GENERATE);
        graph.addEdge(NODE_GENERATE, NODE_QA);
        graph.addConditionalEdges(NODE_QA, edge_async(this::afterQa),
                Map.of("repair", NODE_REPAIR, "publish", NODE_PUBLISH));
        graph.addEdge(NODE_REPAIR, NODE_QA);
        graph.addEdge(NODE_PUBLISH, StateGraph.END);

        CompiledGraph<StudioState> compiledGraph = graph.compile();
        // 回边如果没有上限，质检一直不合格就会把线程挂死
        compiledGraph.setMaxIterations(properties.maxRepairRounds() * 2 + 8);
        return compiledGraph;
    }

    private Map<String, Channel<?>> channels() {
        Map<String, Channel<?>> channels = new LinkedHashMap<>();
        // 所有默认值都必须非 null：langgraph4j 用 Collectors.toMap 收集 schema 初始值，
        // null value 会直接抛 NPE（这个坑踩过一次）。
        channels.put(StudioState.PROMPT, Channels.base(() -> ""));
        channels.put(StudioState.SLUG, Channels.base(() -> ""));
        channels.put(StudioState.PREVIEW_URL, Channels.base(() -> ""));
        channels.put(StudioState.ENHANCED_PROMPT, Channels.base(() -> ""));
        channels.put(StudioState.SPEC, Channels.base(AppSpec::empty));
        channels.put(StudioState.DRAFT, Channels.base(() -> SiteDraft.of(Map.of())));
        channels.put(StudioState.ISSUES, Channels.base(() -> List.<QaIssue>of()));
        channels.put(StudioState.INITIAL_ISSUES, Channels.base(() -> List.<QaIssue>of()));
        channels.put(StudioState.ROUNDS, Channels.base(() -> 0));
        channels.put(StudioState.MODEL, Channels.base(() -> ""));
        channels.put(StudioState.TIER, Channels.base(() -> ""));
        // 阶段名是累积的，用 appender 而不是覆盖
        channels.put(StudioState.STAGES, Channels.appender(ArrayList::new));
        return channels;
    }

    // ------------------------------------------------------------------
    private Map<String, Object> parseNode(StudioState state) {
        String prompt = state.prompt();
        AppSpec spec = specParser.parse(prompt);
        ModelRouter.Route route = router.route(prompt);
        String slug = slugFor(spec, prompt);
        return Map.of(
                StudioState.SPEC, spec,
                StudioState.MODEL, route.model(),
                StudioState.TIER, route.tier(),
                StudioState.SLUG, slug,
                StudioState.STAGES, List.of(NODE_PARSE));
    }

    private Map<String, Object> enhanceNode(StudioState state) {
        AppSpec spec = state.spec();
        String enhanced = """
                原始需求：%s
                应用类型：%s（%s）
                必须满足的约束：
                1. 纯静态 HTML/CSS/JS，不引用任何外部 CDN —— 断网也要能打开；
                2. 结构语义化，表单控件配 label，交互元素有 aria 说明；
                3. 移动优先，640px 以下改单列；
                4. 只实现这一种应用形态，不要发散成通用框架。
                """.formatted(state.prompt(), spec.archetype(), spec.archetype().defaultTitle());
        return Map.of(
                StudioState.ENHANCED_PROMPT, enhanced,
                StudioState.STAGES, List.of(NODE_ENHANCE));
    }

    private Map<String, Object> generateNode(StudioState state) {
        SiteDraft draft = generator.generate(state.spec());
        return Map.of(
                StudioState.DRAFT, draft,
                StudioState.STAGES, List.of(NODE_GENERATE));
    }

    private Map<String, Object> qaNode(StudioState state) {
        List<QaIssue> issues = qaGate.check(state.draft());
        boolean firstPass = state.rounds() == 0 && state.initialIssues().isEmpty();
        return Map.of(
                StudioState.ISSUES, issues,
                StudioState.INITIAL_ISSUES, firstPass ? issues : state.initialIssues(),
                StudioState.STAGES, List.of(NODE_QA));
    }

    private Map<String, Object> repairNode(StudioState state) {
        Repairer.RepairOutcome outcome = repairer.repair(state.draft(), state.issues());
        return Map.of(
                StudioState.DRAFT, outcome.draft(),
                StudioState.ROUNDS, state.rounds() + 1,
                StudioState.STAGES, List.of(NODE_REPAIR));
    }

    private Map<String, Object> publishNode(StudioState state) throws Exception {
        String previewUrl = publisher.publish(state.slug(), state.draft());
        return Map.of(
                StudioState.PREVIEW_URL, previewUrl,
                StudioState.STAGES, List.of(NODE_PUBLISH));
    }

    private String afterQa(StudioState state) {
        if (!state.hasBlockingIssues()) {
            return "publish";
        }
        return state.rounds() >= properties.maxRepairRounds() ? "publish" : "repair";
    }

    private String slugFor(AppSpec spec, String prompt) {
        String prefix = spec.archetype().name().toLowerCase();
        return prefix + "-" + Integer.toHexString(prompt.hashCode()).replace("-", "n");
    }

    // ------------------------------------------------------------------
    /** 流式跑一次：返回的迭代器每消费一个元素就是一个节点执行完（给 SSE 用）。 */
    public Iterable<NodeOutput<StudioState>> stream(String prompt) {
        return compiled.stream(Map.of(StudioState.PROMPT, prompt));
    }

    /** 同步跑一次，返回最终状态。 */
    public GraphOutcome run(String prompt) {
        long started = System.currentTimeMillis();
        Optional<StudioState> finalState = compiled.invoke(Map.of(StudioState.PROMPT, prompt));
        StudioState state = finalState.orElseThrow(() -> new IllegalStateException("工作流没有产出最终状态"));
        return new GraphOutcome(state, state.stages(), System.currentTimeMillis() - started);
    }
}
