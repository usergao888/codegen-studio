package com.gaoyc.studio.gen;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.gaoyc.studio.domain.AppSpec;
import com.gaoyc.studio.domain.SiteDraft;

/**
 * 模板合成式代码生成器。
 *
 * <p>刻意做成确定性实现：同一份 {@link AppSpec} 永远生成同一份产物。这样"质检 → 修复"的
 * 效果可以被单独度量，而不是被模型随机性淹没。接真实模型时，替换本类为"把 spec 渲染进
 * prompt，拿模型返回的文件树"即可，下游质检与发布不用改。
 *
 * <p>生成器会在它自己不确定的写法旁打上 {@code unsafe:} / {@code external:} 标记，
 * 这些标记只用于定位，实际判定仍由质检器按内容规则独立完成。
 */
@Component
public class SiteGenerator {

    private static final String ICON_CDN =
            "https://cdn.jsdelivr.net/npm/lucide@0.400.0/dist/umd/lucide.min.js";

    public SiteDraft generate(AppSpec spec) {
        SiteDraft draft = switch (spec.archetype()) {
            case TODO -> todo(spec);
            case DASHBOARD -> dashboard(spec);
            case LANDING -> landing(spec);
            case FORM -> form(spec);
            case PRICING -> pricing(spec);
            case BLOG -> blog(spec);
        };
        return draft.withFile("styles.css", CssLibrary.styles(spec))
                .withFile("README.md", readme(spec));
    }

    // ------------------------------------------------------------------
    // 页面骨架
    // ------------------------------------------------------------------
    private String shell(AppSpec spec, String body, String extraScripts) {
        String viewport = spec.mobileFirst()
                ? "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
                : "";
        String themeButton = spec.has("dark-mode")
                ? "      <button id=\"theme-toggle\" class=\"icon-btn\" aria-label=\"切换深浅色\">🌙</button>\n"
                : "";
        return Template.render("""
                <!DOCTYPE html>
                <html lang="${lang}">
                <head>
                  <meta charset="UTF-8">
                ${viewport}  <title>${title}</title>
                  <meta name="description" content="${description}">
                  <link rel="stylesheet" href="styles.css">
                </head>
                <body>
                  <div class="wrap">
                    <header class="app-header">
                      <div>
                        <h1>${title}</h1>
                        <p class="subtitle">${subtitle}</p>
                      </div>
                ${themeButton}    </header>
                ${body}  </div>
                ${extraScripts}  <script src="app.js"></script>
                </body>
                </html>
                """,
                "lang", spec.language(),
                "viewport", viewport,
                "title", spec.title(),
                "description", spec.subtitle(),
                "subtitle", spec.subtitle(),
                "themeButton", themeButton,
                "body", body,
                "extraScripts", extraScripts);
    }

    /** 只有需求里点名要图标时才外链图标库 —— 这正是离线预览会失效的那类写法。 */
    private String iconScript(AppSpec spec) {
        if (!spec.has("icons")) {
            return "";
        }
        return "  <!-- external:icon-font -->\n  <script src=\"" + ICON_CDN + "\"></script>\n";
    }

    private String iconInit(AppSpec spec) {
        if (!spec.has("icons")) {
            return "";
        }
        return "  // external:icon-font-init\n  if (window.lucide) { window.lucide.createIcons(); }\n";
    }

    private String themeJs(AppSpec spec) {
        if (!spec.has("dark-mode")) {
            return "";
        }
        return """
                  function initTheme() {
                    var root = document.documentElement;
                    var saved = null;
                    try { saved = localStorage.getItem("studio-theme"); } catch (err) { saved = null; }
                    if (saved === "dark") { root.setAttribute("data-theme", "dark"); }
                    var button = document.getElementById("theme-toggle");
                    if (!button) { return; }
                    button.addEventListener("click", function () {
                      var next = root.getAttribute("data-theme") === "dark" ? "light" : "dark";
                      root.setAttribute("data-theme", next);
                      button.textContent = next === "dark" ? "☀️" : "🌙";
                      try { localStorage.setItem("studio-theme", next); } catch (err) { }
                    });
                  }
                """;
    }

    // ------------------------------------------------------------------
    // 待办清单
    // ------------------------------------------------------------------
    private SiteDraft todo(AppSpec spec) {
        boolean filter = spec.has("filter");
        String filters = filter ? """
                    <div class="filters" role="group" aria-label="筛选任务">
                      <button class="filter is-active" data-filter="all" type="button">全部</button>
                      <button class="filter" data-filter="active" type="button">进行中</button>
                      <button class="filter" data-filter="done" type="button">已完成</button>
                    </div>
                """ : "";

        String body = Template.render("""
                    <form id="add-form" class="add-row" autocomplete="off">
                      <label class="sr-only" for="new-task">新任务</label>
                      <input id="new-task" type="text" placeholder="要做点什么？" maxlength="80" required>
                      <button type="submit">添加</button>
                    </form>
                ${filters}    <ul id="task-list" class="task-list"></ul>
                    <p id="empty-state" class="empty" hidden>还没有任务，先添加一条。</p>
                    <footer class="app-footer">
                      <span id="stat-total">共 0 项</span>
                      <span>·</span>
                      <span id="stat-active">未完成 0</span>
                      <span>·</span>
                      <span id="stat-done">已完成 0</span>
                    </footer>
                """, "filters", filters);

        String js = Template.render("""
                (function () {
                  "use strict";

                  var STORAGE_KEY = "studio-todo-v1";
                  var state = { items: [], filter: "all" };

                  function load() {
                    try {
                      var raw = localStorage.getItem(STORAGE_KEY);
                      var parsed = raw ? JSON.parse(raw) : [];
                      state.items = Array.isArray(parsed) ? parsed : [];
                    } catch (err) {
                      state.items = [];
                    }
                  }

                  function save() {
                    try {
                      localStorage.setItem(STORAGE_KEY, JSON.stringify(state.items));
                    } catch (err) {
                      /* 隐私模式下写入会失败，忽略即可 */
                    }
                  }

                  function visibleItems() {
                    if (state.filter === "active") {
                      return state.items.filter(function (item) { return !item.done; });
                    }
                    if (state.filter === "done") {
                      return state.items.filter(function (item) { return item.done; });
                    }
                    return state.items;
                  }

                  function toggle(id) {
                    state.items = state.items.map(function (item) {
                      return item.id === id ? { id: item.id, text: item.text, done: !item.done } : item;
                    });
                    save();
                    render();
                  }

                  function remove(id) {
                    state.items = state.items.filter(function (item) { return item.id !== id; });
                    save();
                    render();
                  }

                  function render() {
                    var list = document.getElementById("task-list");
                    var empty = document.getElementById("empty-state");
                    list.textContent = "";
                    var items = visibleItems();
                    empty.hidden = items.length > 0;

                    items.forEach(function (task) {
                      var li = document.createElement("li");
                      li.className = "task" + (task.done ? " is-done" : "");

                      var box = document.createElement("input");
                      box.type = "checkbox";
                      box.checked = !!task.done;
                      box.setAttribute("aria-label", "标记完成");
                      box.addEventListener("change", function () { toggle(task.id); });

                      var text = document.createElement("span");
                      text.className = "task-text";
                ${textAssignment}
                      var del = document.createElement("button");
                      del.type = "button";
                      del.className = "delete";
                      del.textContent = "删除";
                      del.addEventListener("click", function () { remove(task.id); });

                      li.appendChild(box);
                      li.appendChild(text);
                      li.appendChild(del);
                      list.appendChild(li);
                    });

                    document.getElementById("stat-total").textContent = "共 " + state.items.length + " 项";
                    document.getElementById("stat-active").textContent =
                      "未完成 " + state.items.filter(function (i) { return !i.done; }).length;
                    document.getElementById("stat-done").textContent =
                      "已完成 " + state.items.filter(function (i) { return i.done; }).length;
                  }

                  function add(text) {
                    state.items = state.items.concat([{
                      id: String(Date.now()) + Math.random().toString(16).slice(2, 6),
                      text: text,
                      done: false
                    }]);
                    save();
                    render();
                  }

                  function init() {
                    load();
                    var form = document.getElementById("add-form");
                    var input = document.getElementById("new-task");
                    form.addEventListener("submit", function (event) {
                      event.preventDefault();
                      var value = input.value.trim();
                      if (!value) { return; }
                      add(value);
                      input.value = "";
                      input.focus();
                    });
                ${filterInit}${themeCall}    render();
                  }

                  init();
                })();
                """,
                "textAssignment",
                spec.richText()
                        ? "  // unsafe:rich-text\n      text.innerHTML = task.text;"
                        : "  text.textContent = task.text;",
                "filterInit",
                filter
                        ? """
                                  Array.prototype.forEach.call(document.querySelectorAll(".filter"), function (button) {
                                    button.addEventListener("click", function () {
                                      Array.prototype.forEach.call(document.querySelectorAll(".filter"), function (other) {
                                        other.classList.remove("is-active");
                                      });
                                      button.classList.add("is-active");
                                      state.filter = button.getAttribute("data-filter");
                                      render();
                                    });
                                  });
                        """
                        : "",
                "themeCall", spec.has("dark-mode") ? "    initTheme();\n" : "");

        String fullJs = Template.render("""
                ${iconInit}${headScript}${body}
                """,
                "iconInit", iconInit(spec),
                "headScript", spec.has("dark-mode") ? themeJs(spec) : "",
                "body", js);

        return SiteDraft.of(Map.of("index.html", shell(spec, body, iconScript(spec)), "app.js", fullJs));
    }

    // ------------------------------------------------------------------
    // 数据看板
    // ------------------------------------------------------------------
    private SiteDraft dashboard(AppSpec spec) {
        String body = """
                    <section class="grid grid-3" style="margin-top:24px">
                      <article class="card kpi">
                        <span class="kpi-label">本周活跃</span>
                        <span class="kpi-value">2,481</span>
                        <span class="kpi-delta">+12.4% 环比</span>
                      </article>
                      <article class="card kpi">
                        <span class="kpi-label">转化率</span>
                        <span class="kpi-value">4.8%</span>
                        <span class="kpi-delta is-down">-0.6% 环比</span>
                      </article>
                      <article class="card kpi">
                        <span class="kpi-label">平均时长</span>
                        <span class="kpi-value">6m 12s</span>
                        <span class="kpi-delta">+38s 环比</span>
                      </article>
                    </section>
                    <section class="card" style="margin-top:16px">
                      <h2 class="section-title">近 7 天访问趋势</h2>
                      <canvas id="trend-chart" height="220" role="img" aria-label="近七天访问量柱状图"></canvas>
                      <table class="sr-only">
                        <caption>近 7 天访问量</caption>
                        <thead><tr><th>日期</th><th>访问量</th></tr></thead>
                        <tbody>
                          <tr><td>周一</td><td>820</td></tr>
                          <tr><td>周二</td><td>932</td></tr>
                          <tr><td>周三</td><td>901</td></tr>
                          <tr><td>周四</td><td>1290</td></tr>
                          <tr><td>周五</td><td>1130</td></tr>
                          <tr><td>周六</td><td>620</td></tr>
                          <tr><td>周日</td><td>540</td></tr>
                        </tbody>
                      </table>
                    </section>
                """;

        String js = Template.render("""
                (function () {
                  "use strict";

                  var DATA = [
                    { label: "周一", value: 820 },
                    { label: "周二", value: 932 },
                    { label: "周三", value: 901 },
                    { label: "周四", value: 1290 },
                    { label: "周五", value: 1130 },
                    { label: "周六", value: 620 },
                    { label: "周日", value: 540 }
                  ];

                  function drawChart() {
                    var canvas = document.getElementById("trend-chart");
                    if (!canvas || !canvas.getContext) { return; }
                    var ctx = canvas.getContext("2d");
                    var width = canvas.clientWidth || 640;
                    var height = canvas.clientHeight || 220;
                    canvas.width = width;
                    canvas.height = height;

                    var styles = getComputedStyle(document.documentElement);
                    var accent = styles.getPropertyValue("--accent").trim() || "#3d6bfb";
                    var border = styles.getPropertyValue("--border").trim() || "#e5e7eb";
                    var muted = styles.getPropertyValue("--muted").trim() || "#6b7280";

                    ctx.clearRect(0, 0, width, height);
                    var max = Math.max.apply(null, DATA.map(function (d) { return d.value; })) * 1.15;
                    var padBottom = 26;
                    var barWidth = (width - 24) / DATA.length * 0.55;
                    var gap = (width - 24) / DATA.length;

                    ctx.strokeStyle = border;
                    ctx.lineWidth = 1;
                    ctx.beginPath();
                    ctx.moveTo(12, height - padBottom);
                    ctx.lineTo(width - 12, height - padBottom);
                    ctx.stroke();

                    DATA.forEach(function (item, index) {
                      var barHeight = (item.value / max) * (height - padBottom - 16);
                      var x = 12 + index * gap + (gap - barWidth) / 2;
                      var y = height - padBottom - barHeight;
                      ctx.fillStyle = accent;
                      ctx.globalAlpha = 0.85;
                      ctx.fillRect(x, y, barWidth, barHeight);
                      ctx.globalAlpha = 1;
                      ctx.fillStyle = muted;
                      ctx.font = "12px sans-serif";
                      ctx.textAlign = "center";
                      ctx.fillText(item.label, x + barWidth / 2, height - 8);
                    });
                  }

                  function init() {
                ${themeCall}    drawChart();
                    window.addEventListener("resize", drawChart);
                  }

                  init();
                })();
                """,
                "themeCall", spec.has("dark-mode") ? "    initTheme();\n" : "");

        String fullJs = Template.render("""
                ${iconInit}${themeFn}${body}
                """,
                "iconInit", iconInit(spec),
                "themeFn", spec.has("dark-mode") ? themeJs(spec) : "",
                "body", js);

        return SiteDraft.of(Map.of("index.html", shell(spec, body, iconScript(spec)), "app.js", fullJs));
    }

    // ------------------------------------------------------------------
    // 产品落地页
    // ------------------------------------------------------------------
    private SiteDraft landing(AppSpec spec) {
        String body = """
                    <section class="hero">
                      <h1>把想法变成页面的最快方式</h1>
                      <p>描述你要什么，平台直接生成可运行的前端代码，自带质检与预览。</p>
                      <div class="cta-row">
                        <button type="button" id="cta-primary">立即试用</button>
                        <button type="button" class="btn-ghost" id="cta-secondary">看看示例</button>
                      </div>
                    </section>
                    <section class="grid grid-3">
                      <article class="card feature">
                        <span class="feature-icon">⚡</span>
                        <h3>一句话生成</h3>
                        <p>描述需求即可产出完整页面，不用写模板代码。</p>
                      </article>
                      <article class="card feature">
                        <span class="feature-icon">🛡️</span>
                        <h3>自动质检</h3>
                        <p>标签闭合、脚本语法、安全写法逐项检查，不合格自动修。</p>
                      </article>
                      <article class="card feature">
                        <span class="feature-icon">🔗</span>
                        <h3>一键分享</h3>
                        <p>生成即发布，拿到链接直接发给同事看效果。</p>
                      </article>
                    </section>
                    <section class="card" style="margin-top:16px">
                      <h2 class="section-title">常见问题</h2>
                      <p class="subtitle">生成的代码没有外部依赖，断网也能打开。</p>
                    </section>
                """;

        String js = Template.render("""
                (function () {
                  "use strict";

                  function init() {
                ${themeCall}    var primary = document.getElementById("cta-primary");
                    if (primary) {
                      primary.addEventListener("click", function () {
                        primary.textContent = "已提交，等待演示";
                        primary.disabled = true;
                      });
                    }
                    var secondary = document.getElementById("cta-secondary");
                    if (secondary) {
                      secondary.addEventListener("click", function () {
                        window.scrollTo({ top: 0, behavior: "smooth" });
                      });
                    }
                  }

                  init();
                })();
                """,
                "themeCall", spec.has("dark-mode") ? "    initTheme();\n" : "");

        String fullJs = Template.render("""
                ${iconInit}${themeFn}${body}
                """,
                "iconInit", iconInit(spec),
                "themeFn", spec.has("dark-mode") ? themeJs(spec) : "",
                "body", js);

        return SiteDraft.of(Map.of("index.html", shell(spec, body, iconScript(spec)), "app.js", fullJs));
    }

    // ------------------------------------------------------------------
    // 信息收集表
    // ------------------------------------------------------------------
    private SiteDraft form(AppSpec spec) {
        String body = """
                    <form id="signup-form" class="card" style="margin-top:24px" novalidate>
                      <div class="field">
                        <label for="name">姓名</label>
                        <input id="name" name="name" type="text" maxlength="40" required>
                        <span class="error" data-for="name"></span>
                      </div>
                      <div class="field">
                        <label for="email">邮箱</label>
                        <input id="email" name="email" type="email" maxlength="80" required>
                        <span class="error" data-for="email"></span>
                      </div>
                      <div class="field">
                        <label for="topic">想了解的内容</label>
                        <select id="topic" name="topic">
                          <option value="product">产品能力</option>
                          <option value="pricing">价格方案</option>
                          <option value="support">技术支持</option>
                        </select>
                        <span class="error" data-for="topic"></span>
                      </div>
                      <div class="field">
                        <label for="note">补充说明</label>
                        <textarea id="note" name="note" rows="4" maxlength="300"></textarea>
                        <span class="error" data-for="note"></span>
                      </div>
                      <button type="submit" id="submit-btn">提交</button>
                      <p id="form-notice" class="notice" hidden></p>
                    </form>
                """;

        String js = Template.render("""
                (function () {
                  "use strict";

                  var EMAIL = /^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$/;

                  function setError(field, message) {
                    var target = document.querySelector('.error[data-for="' + field + '"]');
                    if (target) { target.textContent = message || ""; }
                  }

                  function validate() {
                    var ok = true;
                    var name = document.getElementById("name").value.trim();
                    var email = document.getElementById("email").value.trim();

                    if (name.length < 2) { setError("name", "请填写真实姓名（至少 2 个字）"); ok = false; }
                    else { setError("name", ""); }

                    if (!EMAIL.test(email)) { setError("email", "邮箱格式不正确"); ok = false; }
                    else { setError("email", ""); }

                    return ok;
                  }

                  function init() {
                ${themeCall}    var form = document.getElementById("signup-form");
                    var notice = document.getElementById("form-notice");
                    form.addEventListener("submit", function (event) {
                      event.preventDefault();
                      if (!validate()) { return; }
                      notice.hidden = false;
                      notice.textContent = "已收到，我们会在 1 个工作日内联系你。";
                      form.reset();
                    });
                  }

                  init();
                })();
                """,
                "themeCall", spec.has("dark-mode") ? "    initTheme();\n" : "");

        String fullJs = Template.render("""
                ${iconInit}${themeFn}${body}
                """,
                "iconInit", iconInit(spec),
                "themeFn", spec.has("dark-mode") ? themeJs(spec) : "",
                "body", js);

        return SiteDraft.of(Map.of("index.html", shell(spec, body, iconScript(spec)), "app.js", fullJs));
    }

    // ------------------------------------------------------------------
    // 价格方案页
    // ------------------------------------------------------------------
    private SiteDraft pricing(AppSpec spec) {
        String body = """
                    <div class="cta-row" style="margin:24px 0 18px;justify-content:flex-start">
                      <button type="button" id="billing-toggle">按年付费（省 20%）</button>
                    </div>
                    <section class="grid grid-3" id="plans">
                      <article class="card price-card" data-monthly="0" data-yearly="0">
                        <h3>个人版</h3>
                        <p class="price" data-price>免费</p>
                        <ul class="plan-list">
                          <li>每月 20 次生成</li>
                          <li>基础模板</li>
                        </ul>
                        <button type="button" class="plan-cta">开始使用</button>
                      </article>
                      <article class="card price-card is-featured" data-monthly="99" data-yearly="79">
                        <h3>团队版</h3>
                        <p class="price" data-price>¥99<span> / 月</span></p>
                        <ul class="plan-list">
                          <li>不限次数生成</li>
                          <li>质检报告与版本对比</li>
                          <li>5 个协作席位</li>
                        </ul>
                        <button type="button" class="plan-cta">立即升级</button>
                      </article>
                      <article class="card price-card" data-monthly="299" data-yearly="239">
                        <h3>企业版</h3>
                        <p class="price" data-price>¥299<span> / 月</span></p>
                        <ul class="plan-list">
                          <li>私有化部署</li>
                          <li>自定义模型与知识库</li>
                          <li>专属支持</li>
                        </ul>
                        <button type="button" class="plan-cta">联系销售</button>
                      </article>
                    </section>
                """;

        String js = Template.render("""
                (function () {
                  "use strict";

                  var yearly = false;

                  function render() {
                    Array.prototype.forEach.call(document.querySelectorAll("#plans .price-card"), function (card) {
                      var monthly = Number(card.getAttribute("data-monthly"));
                      var annual = Number(card.getAttribute("data-yearly"));
                      var priceNode = card.querySelector("[data-price]");
                      if (!priceNode) { return; }
                      if (monthly === 0) { priceNode.textContent = "免费"; return; }
                      var value = yearly ? annual : monthly;
                      priceNode.textContent = "¥" + value + (yearly ? " / 月（按年付）" : " / 月");
                    });
                    var toggle = document.getElementById("billing-toggle");
                    if (toggle) {
                      toggle.textContent = yearly ? "切换回按月付费" : "按年付费（省 20%）";
                    }
                  }

                  function init() {
                ${themeCall}    var toggle = document.getElementById("billing-toggle");
                    if (toggle) {
                      toggle.addEventListener("click", function () {
                        yearly = !yearly;
                        render();
                      });
                    }
                    Array.prototype.forEach.call(document.querySelectorAll(".plan-cta"), function (button) {
                      button.addEventListener("click", function () {
                        button.textContent = "已收到意向";
                        button.disabled = true;
                      });
                    });
                    render();
                  }

                  init();
                })();
                """,
                "themeCall", spec.has("dark-mode") ? "    initTheme();\n" : "");

        String fullJs = Template.render("""
                ${iconInit}${themeFn}${body}
                """,
                "iconInit", iconInit(spec),
                "themeFn", spec.has("dark-mode") ? themeJs(spec) : "",
                "body", js);

        return SiteDraft.of(Map.of("index.html", shell(spec, body, iconScript(spec)), "app.js", fullJs));
    }

    // ------------------------------------------------------------------
    // 文章列表页
    // ------------------------------------------------------------------
    private SiteDraft blog(AppSpec spec) {
        String body = """
                    <input id="search" type="text" placeholder="搜索标题或摘要" style="margin:24px 0 8px;width:100%">
                    <div class="card" id="post-list">
                      <article class="post" data-tags="架构 后端">
                        <h3>把 Agent 写成状态图之后，我踩过的三个坑</h3>
                        <p>状态爆炸、检查点滥用、条件边写成死循环。</p>
                      </article>
                      <article class="post" data-tags="检索 评测">
                        <h3>RAG 的 Recall@1 为什么比 BM25 还低</h3>
                        <p>一个关于单字匹配噪声的排查记录。</p>
                      </article>
                      <article class="post" data-tags="工程 规范">
                        <h3>离线环境里怎么验证一个 Spring 项目</h3>
                        <p>不能联网也能跑：手动拼 classpath 与自建测试运行器。</p>
                      </article>
                    </div>
                    <section class="card" id="post-detail" hidden style="margin-top:16px">
                      <h2 class="section-title" id="detail-title"></h2>
                      <p id="detail-body" class="subtitle"></p>
                      <button type="button" class="btn-ghost" id="back-btn">返回列表</button>
                    </section>
                """;

        String js = Template.render("""
                (function () {
                  "use strict";

                  function wire() {
                    var list = document.getElementById("post-list");
                    var detail = document.getElementById("post-detail");
                    var title = document.getElementById("detail-title");
                    var body = document.getElementById("detail-body");
                    var search = document.getElementById("search");

                    Array.prototype.forEach.call(list.querySelectorAll(".post"), function (post) {
                      post.addEventListener("click", function () {
                        title.textContent = post.querySelector("h3").textContent;
                        body.textContent = post.querySelector("p").textContent;
                        list.hidden = true;
                        detail.hidden = false;
                        window.scrollTo({ top: 0, behavior: "smooth" });
                      });
                    });

                    var back = document.getElementById("back-btn");
                    if (back) {
                      back.addEventListener("click", function () {
                        detail.hidden = true;
                        list.hidden = false;
                      });
                    }

                    if (search) {
                      search.addEventListener("input", function () {
                        var keyword = search.value.trim().toLowerCase();
                        Array.prototype.forEach.call(list.querySelectorAll(".post"), function (post) {
                          var text = post.textContent.toLowerCase();
                          post.hidden = keyword.length > 0 && text.indexOf(keyword) < 0;
                        });
                      });
                    }
                  }

                  function init() {
                ${themeCall}    wire();
                  }

                  init();
                })();
                """,
                "themeCall", spec.has("dark-mode") ? "    initTheme();\n" : "");

        String fullJs = Template.render("""
                ${iconInit}${themeFn}${body}
                """,
                "iconInit", iconInit(spec),
                "themeFn", spec.has("dark-mode") ? themeJs(spec) : "",
                "body", js);

        return SiteDraft.of(Map.of("index.html", shell(spec, body, iconScript(spec)), "app.js", fullJs));
    }

    // ------------------------------------------------------------------
    private String readme(AppSpec spec) {
        String features = spec.features().isEmpty() ? "无额外特性" : String.join("、", spec.features());
        return Template.render("""
                # ${title}

                ${subtitle}

                由 AI 零代码应用生成平台生成，纯静态、无外部依赖，双击 `index.html` 或交给任意静态服务器即可运行。

                ## 文件

                | 文件 | 说明 |
                | --- | --- |
                | `index.html` | 页面结构（语义化标签 + 无障碍属性） |
                | `styles.css` | 样式（CSS 变量、响应式、深色模式） |
                | `app.js` | 交互逻辑（原生 JS，无框架、无 CDN） |

                ## 需求规格

                - 应用类型：${archetype}
                - 特性：${features}
                - 主色：${accent}
                - 移动优先：${mobile}

                ## 本地预览

                ```bash
                python -m http.server 8000
                # 打开 http://127.0.0.1:8000
                ```
                """,
                "title", spec.title(),
                "subtitle", spec.subtitle(),
                "archetype", spec.archetype().name(),
                "features", features,
                "accent", spec.accent(),
                "mobile", spec.mobileFirst() ? "是" : "否");
    }
}
