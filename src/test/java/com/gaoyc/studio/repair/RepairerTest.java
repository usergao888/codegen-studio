package com.gaoyc.studio.repair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.gaoyc.studio.domain.QaIssue;
import com.gaoyc.studio.domain.SiteDraft;
import com.gaoyc.studio.gen.SiteGenerator;
import com.gaoyc.studio.qa.CssValidator;
import com.gaoyc.studio.qa.HtmlValidator;
import com.gaoyc.studio.qa.JsValidator;
import com.gaoyc.studio.qa.PolicyValidator;
import com.gaoyc.studio.qa.QaGate;
import com.gaoyc.studio.spec.SpecParser;

public class RepairerTest {

    private final SpecParser parser = new SpecParser();
    private final SiteGenerator generator = new SiteGenerator();
    private final Repairer repairer = new Repairer();
    private final QaGate gate = new QaGate(
            new HtmlValidator(), new CssValidator(), new JsValidator(), new PolicyValidator());

    private List<String> errorCodes(SiteDraft draft) {
        return gate.check(draft).stream()
                .filter(QaIssue::isError)
                .map(QaIssue::code)
                .collect(Collectors.toList());
    }

    @Test
    void repairsXssBySwitchingToTextContent() {
        SiteDraft dirty = generator.generate(parser.parse("做一个待办应用，支持富文本备注"));
        assertTrue(errorCodes(dirty).contains("XSS_INNERHTML"));

        Repairer.RepairOutcome outcome = repairer.repair(dirty, gate.check(dirty));
        assertFalse(outcome.draft().file("app.js").contains("innerHTML = "));
        assertFalse(errorCodes(outcome.draft()).contains("XSS_INNERHTML"));
        assertTrue(outcome.applied().stream().anyMatch(s -> s.contains("XSS_INNERHTML")));
    }

    @Test
    void repairsXssInNestedMultifileJs() {
        SiteDraft nested = com.gaoyc.studio.gen.MultiFileLayout.apply(
                generator.generate(parser.parse("做一个待办清单")));
        SiteDraft dirty = nested.withFile("assets/js/app.js",
                "function render(v) { node.innerHTML = v; }");

        Repairer.RepairOutcome outcome = repairer.repair(dirty, gate.check(dirty));
        assertFalse(outcome.draft().file("assets/js/app.js").contains("innerHTML = "));
        assertFalse(errorCodes(outcome.draft()).contains("XSS_INNERHTML"));
    }

    @Test
    void repairsMissingViewport() {
        SiteDraft dirty = generator.generate(parser.parse("给 PC 端做一个官网介绍页"));
        Repairer.RepairOutcome outcome = repairer.repair(dirty, gate.check(dirty));
        assertTrue(outcome.draft().file("index.html").contains("name=\"viewport\""));
        assertFalse(errorCodes(outcome.draft()).contains("HTML_MISSING_VIEWPORT"));
    }

    @Test
    void repairsExternalAssets() {
        SiteDraft dirty = generator.generate(parser.parse("做一个待办清单，要图标"));
        assertTrue(dirty.file("index.html").contains("https://"));

        Repairer.RepairOutcome outcome = repairer.repair(dirty, gate.check(dirty));
        assertFalse(outcome.draft().file("index.html").contains("https://"));
        assertFalse(errorCodes(outcome.draft()).contains("EXTERNAL_ASSET"));
    }

    @Test
    void repairsUnclosedTags() {
        SiteDraft clean = generator.generate(parser.parse("做一个待办清单"));
        SiteDraft dirty = clean.withFile("index.html", clean.file("index.html").replace("</main>", ""));
        Repairer.RepairOutcome outcome = repairer.repair(dirty, gate.check(dirty));
        assertFalse(errorCodes(outcome.draft()).contains("HTML_UNCLOSED_TAG"));
    }

    @Test
    void repairIsIdempotentOnCleanDraft() {
        SiteDraft clean = generator.generate(parser.parse("做一个待办清单"));
        Repairer.RepairOutcome outcome = repairer.repair(clean, gate.check(clean));
        assertEquals(List.of(), outcome.applied());
        assertEquals(clean.file("app.js"), outcome.draft().file("app.js"));
    }

    @Test
    void unknownIssueCodeIsLeftAlone() {
        SiteDraft clean = generator.generate(parser.parse("做一个待办清单"));
        Repairer.RepairOutcome outcome = repairer.repair(clean,
                List.of(QaIssue.error("SOMETHING_ELSE", "index.html", "未知问题")));
        assertEquals(List.of(), outcome.applied());
        assertEquals(clean.file("index.html"), outcome.draft().file("index.html"));
    }
}
