package com.gaoyc.studio.domain;

import java.util.List;
import java.util.Map;

import org.bsc.langgraph4j.state.AgentState;

/** 工作流里流转的状态。键名集中定义，避免各处硬编码字符串写错。 */
public class StudioState extends AgentState {

    public static final String PROMPT = "prompt";
    public static final String SLUG = "slug";
    public static final String PREVIEW_URL = "previewUrl";
    public static final String ENHANCED_PROMPT = "enhancedPrompt";
    public static final String SPEC = "spec";
    public static final String DRAFT = "draft";
    public static final String ISSUES = "issues";
    public static final String INITIAL_ISSUES = "initialIssues";
    public static final String ROUNDS = "rounds";
    public static final String STAGES = "stages";
    public static final String MODEL = "model";
    public static final String TIER = "tier";

    public StudioState(Map<String, Object> initData) {
        super(initData);
    }

    public String prompt() {
        return String.valueOf(data().getOrDefault(PROMPT, ""));
    }

    public String slug() {
        return String.valueOf(data().getOrDefault(SLUG, ""));
    }

    public String previewUrl() {
        return String.valueOf(data().getOrDefault(PREVIEW_URL, ""));
    }

    public String model() {
        return String.valueOf(data().getOrDefault(MODEL, ""));
    }

    public String tier() {
        return String.valueOf(data().getOrDefault(TIER, ""));
    }

    public String enhancedPrompt() {
        return String.valueOf(data().getOrDefault(ENHANCED_PROMPT, ""));
    }

    public AppSpec spec() {
        return (AppSpec) data().get(SPEC);
    }

    public SiteDraft draft() {
        return (SiteDraft) data().get(DRAFT);
    }

    @SuppressWarnings("unchecked")
    public List<QaIssue> issues() {
        Object value = data().get(ISSUES);
        return value instanceof List<?> list ? (List<QaIssue>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    public List<QaIssue> initialIssues() {
        Object value = data().get(INITIAL_ISSUES);
        return value instanceof List<?> list ? (List<QaIssue>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    public List<String> stages() {
        Object value = data().get(STAGES);
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }

    public int rounds() {
        Object value = data().get(ROUNDS);
        return value instanceof Integer i ? i : 0;
    }

    public boolean hasBlockingIssues() {
        return issues().stream().anyMatch(QaIssue::isError);
    }
}
