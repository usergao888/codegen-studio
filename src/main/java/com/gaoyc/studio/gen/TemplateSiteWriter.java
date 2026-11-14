package com.gaoyc.studio.gen;

import org.springframework.stereotype.Component;

import com.gaoyc.studio.domain.AppSpec;
import com.gaoyc.studio.domain.SiteDraft;

/** 模板合成后端：确定性、零依赖，任何时候都能出结果，也是 LLM 失败时的兜底。 */
@Component
public class TemplateSiteWriter implements SiteWriter {

    private final SiteGenerator generator;

    public TemplateSiteWriter(SiteGenerator generator) {
        this.generator = generator;
    }

    @Override
    public SiteDraft write(AppSpec spec, String prompt) {
        return generator.generate(spec);
    }

    @Override
    public String backend() {
        return "template";
    }
}
