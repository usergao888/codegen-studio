# AI 零代码应用生成平台（codegen-studio）

> 一句话描述需求 → 六节点 Agent 工作流 → 产出可运行的前端代码 → 自动质检 → 定向修复 → 立刻可预览。

**Java 21 · Spring Boot 3.5.4 · LangGraph4j 1.6.0-rc2 · Caffeine + Redis · Micrometer · HITL · 可接真实模型 · 71 个单元测试**

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
| 单元测试 | **54 个，全绿** |
| 三类注入缺陷 | 全部被检出，**1 轮修复后通过**（首轮拦截率 3/3） |
| 产物可执行性 | 4 个生成站点全部通过 `node --check` |
| 限流 | 同一用户 25 次连打：18 次 200 + **7 次 429**；Redis 里可见 user/ip/endpoint 三级桶 |
| 缓存 | 同一需求二次请求命中 L1，`cached=true`，耗时从 ~140ms 降到 <1ms |
| 单次生成延迟 | 平均 78ms（离线确定性生成器，不含真实模型耗时） |
| 可观测性 | `/actuator/prometheus` 暴露 `studio_*` 业务指标 + JVM/HTTP 内置指标，Grafana 可直接抓 |
| 生成历史 | JSONL 落盘（`var/runs/runs.jsonl`），`/api/runs` 可倒序查最近 N 条，重启后回放 |
| 发布确认（HITL） | `require-approval=true` 时质检通过后停在发布前：不落盘、无预览地址，`approve` 才发布、`reject` 丢弃 |
| 真实模型 | `studio.llm.enabled=true` 时走 OpenAI 兼容端点（DeepSeek/Qwen/vLLM/Ollama）；失败自动回退模板 |
| 单元测试 | **71 个，全绿** |

---

### 两级缓存为什么默认是内存、而不是直接写死 Redis

缓存是加速手段，不是可用性前提。所以 L2 被抽成 `L2Store` 接口：

- `InMemoryL2`（默认）：单机够用，没有外部依赖
- `RedisL2`：`studio.redis.enabled=true` 时启用，多实例共享
- **连不上就回退**：启动时 ping 一次，失败则记 WARN 并退回内存实现，服务照常可用

实测（本机 Redis 3.0.504）：写入 key `studio:gen:<规范化需求>`、TTL 1800s；
**杀掉进程重启后再请求同一需求，`cached=true` 且 `l2Hits=1`、`l1Hits=0`** ——
说明命中的确实是 Redis，而不是残留的进程内缓存。

```yaml
studio:
  cache-ttl-seconds: 1800
  redis:
    enabled: true        # 关掉就退回内存实现
    host: 127.0.0.1
    port: 6379
    database: 0
    key-prefix: "studio:gen:"
```

---

### 限流为什么要分三级、为什么必须用 Lua

只按用户限流，换个账号就能刷；只按 IP 限流，同出口的同事互相拖累；只按接口限流，一个人能把整个接口吃满。
所以三级各自算桶、任意一级拒绝即整体拒绝：`user 5/s`、`ip 20/s`、`endpoint 50/s`。

判断必须原子：如果拆成 GET → 算 → SET 三次往返，两个并发请求会读到同一份 tokens 再各自扣减，
限流器自己就被击穿了。这里用一段 Lua 做"读桶 → 回补 → 扣减 → 写回"，
Redis 里存的是 `studio:rl:<级别>:<取值>` 的 hash（`tokens` + `ts`），闲置 2 分钟自动回收。

### 可观测性：两套指标，各管一件事

- `/api/metrics`：给人看的业务快照（生成数、命中率、缺陷码分布、成本），字段随业务改
- `/actuator/prometheus`：给监控系统抓的（Micrometer 打点），指标名与 tag 保持稳定

硬合成一套的结果是两边都不好用 —— 业务字段经常变，而监控指标名一改整套看板就废了。
Prometheus 侧目前暴露 `studio_generations_total`、`studio_cache_hits_total`、
`studio_rate_limited_total`、`studio_generation_latency_milliseconds`、
`studio_qa_issues_total{code=...}` 等；Grafana 侧只需把这个端点加为数据源，本仓库不代管 Grafana。

### 生成历史为什么用 JSONL 而不是数据库

本地仓库里没有 H2/HSQLDB/SQLite 的 jar，为了几十条历史引 ORM 不划算。JSONL 的两个好处：

- **按行追加天然容忍半行损坏**：进程被杀最多丢最后一行，不会整份文件读不出来
- 启动时回放到内存索引，只保留最近 200 条，更早的留在文件里

代价是没有索引和事务，适合"看历史/复盘"，不适合当业务主库 —— 真要换 JDBC，只需另写一个
`RunRepository` 实现。

### 为什么发布前要加人工确认

生成的是**会内联脚本的网页**，直接发布等于把一个可能有问题的页面挂出去。机器质检拦得住已知模式
（XSS 写法、外链、标签不闭合），但"这个页面到底能不能给人看"是人的判断 —— 所以这一层不该省。

行为（`studio.require-approval=true`）：

1. 工作流跑到质检通过就停住，产物只留在内存，**不写盘、不给预览地址**
2. `GET /api/approvals` 列出待确认项
3. `POST /api/runs/{id}/approve` 才落盘并返回预览地址；`reject` 直接丢弃
4. 历史里的状态用"追加同 runId 的记录"表达（pending → published/rejected），文件仍是 append-only

实测：pending 时目录不存在、previewUrl 为空；approve 后目录出现、预览 200；reject 后目录始终不存在。

### 真实模型接入：为什么不用 LangChain4j

这里只需要"发一次请求、拿一段文本"，引一整套框架换来的是传递依赖和版本地狱。所以直接用 JDK 自带的
`HttpClient`（零新增依赖），把复杂度放在真正难的地方 —— **输出校验与回退**：

1. 只让模型返回 `{"files":[{"path","content"}]}`，禁止自由文本，否则解析就是赌博；
2. 路径消毒：`../`、绝对路径一律丢弃（模型真的会给出这种路径）；
3. 任何异常（超时/限流/非 2xx/非法 JSON）都回退模板生成，用户永远拿得到能用的页面；
4. 产出的后端名打点到 `studio_generation_backend_total{backend="llm:xxx"}`，两种方案可对比。

**没有 API Key 也能验证这条链路**：`scripts/stub-openai.py` 是一个本地 OpenAI 兼容桩服务，
返回固定文件树，起它然后把 `studio.llm.base-url` 指过去即可。实测产物里带桩标记、
预览 200、指标里出现 `backend="llm:stub-model"`。

> 诚实边界：这里验证的是"调用、解析、消毒、回退"四件事，**没有验证真实模型（DeepSeek/Qwen）的产物质量** ——
> 那需要 API Key 和预算，属于你拿到 key 后的下一步。

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
- 未接 Redisson：分布式限流用自写的 Lua 令牌桶实现（Redis 3.0 就能跑），没有引入 Redisson 客户端；语义等价，仅少了 Redisson 自带的 fair-lock 等扩展。
