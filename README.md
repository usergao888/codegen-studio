# AI 零代码应用生成平台（codegen-studio）

> 一句话描述需求 → 六节点 Agent 工作流 → 产出可运行的前端代码 → 自动质检 → 定向修复 → 立刻可预览。

**Java 21 · Spring Boot 3.5.4 · LangGraph4j 1.6.0-rc2 · Caffeine · 45 个单元测试**

这不是"调一次模型把代码吐出来"的 demo。生成之后的**质检与修复**才是这个项目的重心：
产物要能被浏览器打开、能被 `node --check` 解析、不能有 XSS 和内网依赖，不达标就自动改，改不动就如实报告。

---

## 一、工作流

```
START → parse → enhance → generate → qa ─┬─(无阻塞缺陷)──────────────→ publish → END
                                          └─(有缺陷且未超轮次) → repair → qa
```

| 节点 | 干什么 | 关键实现 |
| --- | --- | --- |
| **parse** | 一句话 → 结构化 `AppSpec` | `SpecParser`：识别 6 种应用形态、8 类特性、主色、是否移动优先；同时决定模型档位 |
| **enhance** | 补全"生成约束" | 把"不要外链 / 语义化 / 移动优先"写进增强提示词 |
| **generate** | 合成 `index.html` + `styles.css` + `app.js` + `README.md` | `SiteGenerator`：模板合成，确定性输出 |
| **qa** | 四路校验 | HTML 标签栈、CSS 变量与括号、`node --check` 真解析、安全与可移植性规则 |
| **repair** | 按缺陷码定向改 | `Repairer`：一个缺陷码对应一条确定改法，改了什么会写进日志 |
| **publish** | 落盘 + 预览地址 | `SitePublisher`：防目录穿越，产物直接由 `/preview/{slug}/` 提供 |

图用 **LangGraph4j 的 `StateGraph`** 编排：节点是 `AsyncNodeAction`，质检后走 `addConditionalEdges`
决定"修复"还是"发布"，回边有 `maxRepairRounds` 兜底。

---

## 二、为什么"质检"值得单独做一层

生成器并不完美，而且**它知道自己哪里不完美**：需求里出现"富文本"时，它按最省事的方式写
`innerHTML = task.text`；出现"图标"时会挂一个 CDN 图标库；说"PC 端"时会省掉 viewport。
这些都是真实世界里生成代码最常见的坑，也正是质检要抓的：

| 缺陷码 | 触发场景 | 判定方式 | 修复动作 |
| --- | --- | --- | --- |
| `XSS_INNERHTML` | 需求含"富文本/备注样式" | `innerHTML` 右边不是字符串字面量 | 改成 `textContent` |
| `EXTERNAL_ASSET` | 需求含"图标" | `src`/`href` 指向 http(s) | 删除外链与其调用点，保留无依赖降级 |
| `HTML_MISSING_VIEWPORT` | 需求含"PC 端" | 没有 `name="viewport"` | 插入 viewport meta |
| `HTML_UNCLOSED_TAG` | 任意 | 标签栈不平衡 | 补齐结束标签 |
| `JS_SYNTAX` | 任意 | **`node --check` 真跑一遍** | 不留自动修复，交给人（语法错误信息已定位到行） |

`node --check` 是整套质检里唯一的外部权威判定 —— 正则永远无法证明 JS 语法合法，V8 可以。
机器上没有 node 时降级为 `JS_CHECK_SKIPPED` 警告，不阻塞发布。

---

## 三、快速开始

```powershell
# 1. 选 JDK（会自动挑本机最高版本，装 25 之后重跑一次即可）
. .\scripts\use-jdk.ps1

# 2. 构建（离线可用，依赖已在本地 Maven 仓库）
.\scripts\build.ps1

# 3. 跑测试（45 个）
.\scripts\test.ps1

# 4. 启动
.\scripts\run.ps1
# → http://127.0.0.1:18080
```

生成一个页面：

```powershell
$body = @{ prompt = "做一个待办清单，要能筛选和深色模式"; userId = "u1" } | ConvertTo-Json -Compress
$bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
Invoke-RestMethod -Uri http://127.0.0.1:18080/api/generate -Method POST `
  -Body $bytes -ContentType "application/json; charset=utf-8"
```

返回里带 `previewUrl`，浏览器打开就是刚生成的应用（纯静态、无 CDN、断网可用）。

### 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/generate` | `{prompt, userId}` → 生成结果（含文件内容、缺陷、轮次、成本） |
| GET | `/api/generate/stream?prompt=&userId=` | SSE：每个节点一条 `node` 事件，最后一条 `result` |
| GET | `/api/metrics` | 生成次数、缓存命中、限流次数、缺陷码分布 |
| GET | `/api/health` | 探活 |
| GET | `/preview/{slug}/{file}` | 预览生成结果 |

---

## 四、实测结果

| 项目 | 数值 |
| --- | --- |
| 支持的应用形态 | 6 种（待办 / 看板 / 落地页 / 表单 / 价格页 / 博客） |
| 单元测试 | **45 个，全绿** |
| 三类注入缺陷 | 全部被检出，**1 轮修复后通过**（首轮拦截率 3/3） |
| 产物可执行性 | 4 个生成站点全部通过 `node --check` |
| 限流 | 同一用户 25 次连打：17 次 200 + **8 次 429**（burst=10, 5/s） |
| 缓存 | 同一需求二次请求命中 L1，`cached=true`，耗时从 ~140ms 降到 <1ms |
| 单次生成延迟 | 平均 78ms（离线确定性生成器，不含真实模型耗时） |

---

## 五、三次"交学费"的记录

这三条都是真实调试出来的，写下来比功能列表有用：

1. **LangGraph4j 的 channel 默认值不能是 `null`。** 它内部用 `Collectors.toMap` 收集 schema 初始值，
   遇到 null value 直接 NPE，报错栈还指向流式代码，第一次看完全不知所云。
2. **状态对象必须可序列化。** langgraph4j 每个节点后会用 Java 序列化克隆一次状态
   （`CompiledGraph.cloneState`），`SiteDraft`/`AppSpec` 没实现 `Serializable` 就会在第二个节点炸掉。
   顺带一提：这也意味着状态里只该放小对象。
3. **两级缓存的 L1 也得有 TTL。** 最初只有 L2 带 TTL，结果 L1 命中时读到的是过期数据；
   而且 Caffeine 用系统时钟，注入假时钟只能测到 L2 —— 必须把 `ticker` 一起换掉。
   **这条是单测抓出来的，不是我读代码看出来的。**

---

## 六、目录结构

```
codegen-studio/
├── src/main/java/com/gaoyc/studio/
│   ├── StudioApplication.java
│   ├── api/            StudioController（生成/SSE/指标）、PreviewController（预览）
│   ├── graph/          GenerationGraph（LangGraph4j 编排 + 六个节点）
│   ├── spec/           SpecParser（一句话 → AppSpec）
│   ├── gen/            SiteGenerator + CssLibrary + Template
│   ├── qa/             QaGate、HtmlValidator、CssValidator、JsValidator、PolicyValidator
│   ├── repair/         Repairer（缺陷码 → 定向修复）
│   ├── cache/          TwoLevelCache（Caffeine L1 + TTL Map L2）
│   ├── limit/          TokenBucketRateLimiter
│   ├── llm/            ModelRouter（复杂度档位 + 成本核算）
│   ├── publish/        SitePublisher
│   ├── metrics/        StudioMetrics
│   └── domain/         AppSpec / SiteDraft / QaIssue / StudioState / GenerationResult
├── src/test/java/      45 个 JUnit 5 用例 + TestMain（离线反射运行器）
├── scripts/            use-jdk / build / test / run
└── var/                生成产物与 classpath 缓存（不入库）
```

---

## 七、已知限制

- **生成器是模板合成，不是模型生成。** 这样做的好处是确定性、可评测；接真实模型时替换
  `SiteGenerator` 即可（把 `AppSpec` 渲染进 prompt，拿回文件树），质检、修复、发布都不用改。
- **`ModelRouter` 目前只做成本核算与展示**，没有真的发请求；接 LangChain4j 时把 `route().model()`
  塞进请求参数即可（`langchain4j-open-ai` 已在依赖里）。
- **离线构建依赖本地 Maven 仓库**：这台机器的 `~/.m2` 里没有
  `maven-surefire-plugin` 的完整依赖链，所以 `mvn test` 跑不了，改用 `TestMain` 反射运行；
  换到有网的机器上，`mvn test` 能直接跑同一批用例。
- 未做：生成结果的像素级回归对比、多文件项目（Vue3 SFC）生成、真实模型的流式 token 透传。
