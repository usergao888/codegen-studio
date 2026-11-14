package com.gaoyc.studio.llm;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.gaoyc.studio.config.StudioProperties;

/**
 * 多模型路由：按需求复杂度挑档位，并把成本算成钱。
 *
 * <p>规则故意简单可解释：需求越长、要素越多（筛选/图表/深色…）、越不是纯展示页，
 * 就越可能生成出错，于是走强模型。真正接 LLM 时，这里返回的 model 直接进请求参数；
 * 离线模式下只用于成本核算与展示。
 */
@Component
public class ModelRouter {

    private final StudioProperties properties;

    public ModelRouter(StudioProperties properties) {
        this.properties = properties;
    }

    public record Route(String model, String tier, double inputCostCnyPer1k, double outputCostCnyPer1k) {
        public double estimateCost(int promptChars) {
            double inputTokens = promptChars / 1.6;
            double outputTokens = 900;      // 生成一份页面的粗略输出规模
            return inputTokens / 1000.0 * inputCostCnyPer1k + outputTokens / 1000.0 * outputCostCnyPer1k;
        }
    }

    private static final List<String> COMPLEX_HINTS =
            List.of("图表", "深色", "筛选", "导出", "表单", "多语言", "权限", "登录", "接口", "富文本");

    public Route route(String prompt) {
        String text = prompt == null ? "" : prompt;
        int hits = 0;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String hint : COMPLEX_HINTS) {
            if (lower.contains(hint)) {
                hits++;
            }
        }
        boolean complex = text.length() >= properties.models().complexPromptChars() || hits >= 2;
        StudioProperties.Models models = properties.models();
        return complex
                ? new Route(models.strong(), "strong", 0.004, 0.016)
                : new Route(models.small(), "small", 0.001, 0.002);
    }
}
