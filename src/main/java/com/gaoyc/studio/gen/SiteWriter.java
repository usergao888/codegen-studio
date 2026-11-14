package com.gaoyc.studio.gen;

import com.gaoyc.studio.domain.AppSpec;
import com.gaoyc.studio.domain.SiteDraft;

/**
 * 产物生成策略。
 *
 * <p>抽成接口是为了让"模板合成"和"大模型生成"可以互换，而且**失败时能回退**：
 * 模型超时/返回不合法 JSON 时退回模板，用户仍然拿得到可用的页面。
 */
public interface SiteWriter {

    SiteDraft write(AppSpec spec, String prompt);

    /** 实际生效的后端名，会写进历史记录，便于对比两种产物的质量。 */
    String backend();
}
