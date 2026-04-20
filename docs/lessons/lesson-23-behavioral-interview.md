# Lesson 23: Behavioral Interview Prep — STAR Method & Project Storytelling

## Behavioral vs Technical Interview

| | Technical | Behavioral |
|---|---|---|
| **問咩** | 點樣設計 / implement | 你做過咩、點樣處理困難 |
| **考咩** | 技術能力 | Soft skills、problem-solving、teamwork |
| **例子** | "Design a cache system" | "Tell me about a time you debugged a hard problem" |

---

## STAR Method

```
S — Situation:  背景係咩？
T — Task:       你要做咩？
A — Action:     你具體做咗咩？（最重要，要 detailed）
R — Result:     結果係咩？學到咩？
```

### Key Principles

1. **Action 要 detailed** — 唔好跳過 debugging 過程，呢個係最精彩嘅部分
2. **Frame as deliberate decision** — 唔好講「做唔到」，講「我 evaluate 過然後決定」
3. **Show systematic approach** — 面試官想睇你一步步 trace，唔係「一眼就知」
4. **End with learning** — Result 要包含你從中學到咩

---

## Story Bank: Questions → Stories 配對

| 面試問題 | 用邊個 Story |
|---|---|
| "Most challenging bug?" | **Story A**: ShardingSphere cross-shard crash (safer, side project) |
| "Debug under pressure?" | **Story B**: RocketMQ broker IP timeout |
| "Difficult trade-off?" | **Story C**: Monolith vs Microservices decision |
| "Production incident / learned from mistake?" | **Story D**: FDR DB connection pool (real work, higher impact) |
| "Time you improved a process?" | Silent 500 fix — 加 log.error 改善 observability |
| "Disagree with a decision?" | 堅持 monolith 而唔係跟 microservices trend |

### Story A vs Story D — 幾時用邊個？

| | Story A (ShardingSphere) | Story D (FDR) |
|---|---|---|
| **Scope** | Side project | Real work production |
| **Impact** | Self-caused bug, no user impact | Real users affected by slow transactions |
| **Risk** | Low — it's a learning project | Higher — involves admitting a design mistake |
| **Reward** | Moderate — shows debugging skill | High — shows ownership, growth, real-world experience |
| **Best for** | Strict/traditional interviewer, junior-mid roles | Open culture, mid-senior roles, "mistake" questions |

---

## Story A: ShardingSphere Cross-Shard Crash

**Question: "Tell me about the most challenging bug you've encountered."**

**S:**
> I was implementing database sharding with ShardingSphere to split the orders table across two MySQL instances by user_id.

**T:**
> After setting up sharding, the checkout endpoint started returning 500 errors. I needed to find the root cause and fix it.

**A:**
> 1. First problem: the 500 error had **no stack trace in the logs** — our GlobalExceptionHandler was catching exceptions but not logging them. So I added `log.error()` to the handler.
> 2. With the stack trace visible, I found the error: **"all tables must be in the same compute node"** — a cross-shard JOIN.
> 3. I traced the SQL and discovered that `Product.findById()` was triggering LEFT JOINs to Category and User tables on different shards.
> 4. Root cause: `@ManyToOne` defaults to **EAGER fetch** in JPA — Hibernate automatically joins related tables, which doesn't work across shards.
> 5. Fix: Changed `@ManyToOne(fetch = FetchType.LAZY)` on Product's category and seller fields.
> 6. Also created a separate Spring Profile for sharding config, so daily development uses a single MySQL without ShardingSphere complexity.

**R:**
> Checkout worked correctly after the fix. I learned two important lessons: **always log exceptions in global error handlers** — a silent 500 is the worst kind of bug. And **understand your ORM's default behavior** — EAGER fetch is JPA's default for @ManyToOne and it can cause serious issues in distributed database environments.

### Why this story is good:
- **Systematic approach** — 一步步 trace，唔係亂試
- **Multiple layers** — 先解決冇 log 嘅問題，再解決真正嘅 bug
- **Root cause analysis** — 唔係表面 fix，而係搵到 EAGER fetch 呢個根本原因
- **Learning** — 從中學到咩，點樣防止再發生

---

## Story D: FDR DB Connection Pool (Real Work Story)

**Question: "Tell me about a production incident you debugged" / "Tell me about a mistake you made and what you learned."**

**S:**
> In a previous role, I built an integration between our POS system and a third-party payment API called FDR, using Mule ESB as middleware. To support refund scenarios, I designed a database table that stored the request and response payloads of every API call — so we could look up the original transaction and modify parameters when a refund was needed.

**T:**
> The system worked fine for the first 2-3 days in production. Then transactions started becoming extremely slow. Users were seeing timeouts. I needed to diagnose the issue quickly.

**A:**
> 1. Checked the Mule logs first — saw **"connection pool exhausted"** errors. That told me it was a DB-side issue, not a network or FDR issue.
> 2. Looked at the flow: **POS → Mule writes request to DB → Mule calls FDR asynchronously** (doesn't wait for response). A separate cron job periodically scans the table for rows with null `responsePayload` and voids those transactions as timeouts.
> 3. Checked my table design — and that's when I realized my mistake. **I only had a composite string primary key (yyyymmdd + random 6 digits), no index on the columns the cron job was scanning.** As the table grew, the cron job was doing full table scans, holding connections for longer and longer, eventually exhausting the pool.
> 4. Worked with our DBA to redesign the schema:
>    - Added two INT columns: `isTimeout` (default 1) and `isReversed` (default 0)
>    - Added a **composite index on (isTimeout, isReversed)**
>    - State machine: new row starts as `isTimeout=1, isReversed=0`. When FDR response arrives, set `isTimeout=0`. When void succeeds, set `isReversed=1`.
>    - The cron job query became `WHERE isTimeout=1 AND isReversed=0` — hits the index, runs in milliseconds.

**R:**
> After deploying the fix, connection pool issues disappeared and transaction latency returned to normal. Two lessons stuck with me:
> 1. **Database design isn't just about storing data — it's about how the data will be queried.** I didn't think about what queries the cron job would run against my table.
> 2. **Async + polling patterns need a state machine.** A boolean/enum column with an index is much better than "scan for nulls."
>
> Since then, I always ask early in design: "What are the access patterns? What queries need to be fast?"

### Why this story is good:
- **Real production incident** — 唔係 side project，面試 weight 更高
- **Ownership** — 承認係自己嘅 design mistake
- **Collaboration** — 同 DBA 合作搵 root cause
- **Cross-system thinking** — POS, Mule, DB, FDR, cron job — 展示你識 reason about distributed flows
- **Concrete learning** — 兩個 specific takeaways 可以 apply 到之後嘅 design

### Expected Follow-ups & How to Answer

**Follow-up 1 (friendly): "What would you do differently now?"**

> "I'd start with the access patterns before the schema. Before designing the table, I'd ask: what queries will hit this table, how often, and from what processes? The cron job scan was a predictable pattern — if I had thought about it upfront, the index would have been obvious."

**Follow-up 2 (adversarial): "Why didn't you consider indexing upfront? Isn't that basic?"**

> "Honestly, at that point in my career I was focused on getting the business logic right — making sure the data model captured what we needed for refunds and void flows. I didn't have the operational experience yet to anticipate how the cron job's query pattern would interact with a growing table. **That incident is exactly what taught me to think about read patterns, not just data shape.** It's the reason I now always ask about access patterns in schema design."

**Key principle for the adversarial version:**
- 認 without being defensive
- Frame 當時 level 嘅限制，唔係 excuse
- 強調 **"that incident is exactly what taught me"** — 將 mistake 轉成 growth

---

## Story B: RocketMQ Broker IP Timeout

**Question: "Tell me about a time you had to debug a complex infrastructure issue."**

**S:**
> I was implementing async checkout using RocketMQ transaction messages. RocketMQ was running in Docker containers.

**T:**
> The checkout process timed out with "sendDefaultImpl call timeout". I needed to find why the app couldn't communicate with the broker.

**A:**
> 1. First I checked if RocketMQ containers were running — `docker ps` confirmed both nameserver and broker were up.
> 2. The error was specifically on **send**, not on connecting to the name server — so the name server was reachable but the **broker** wasn't.
> 3. I checked the broker config and found `brokerIP1 = host.docker.internal`. This is the IP the broker **advertises** to clients — telling them "connect to me at this address."
> 4. I tested the resolution: `host.docker.internal` was resolving to `192.168.x.x`, an internal network IP that was **unreachable** from my app running outside Docker.
> 5. Changed `brokerIP1 = 127.0.0.1` in broker.conf and restarted the broker.

**R:**
> Checkout worked immediately — the transaction message was sent and the consumer received the order event. The key learning: **when services run in Docker, pay attention to the IP they advertise to external clients.** The broker was healthy inside Docker, but advertising an unreachable IP to the outside world. This is a common pitfall in containerized environments.

### Why this story is good:
- **Container networking knowledge** — 識分 container 內部 vs 外部通訊
- **Logical elimination** — nameserver OK → broker 嘅問題 → advertised IP
- **Relatable** — 好多團隊都踩過 Docker networking 嘅坑

---

## Story C: Monolith vs Microservices Decision

**Question: "Tell me about a difficult technical trade-off you had to make."**

**S:**
> I was building an e-commerce backend with features like async checkout, caching, search, and database sharding. I had to decide whether to build it as a monolith or microservices architecture.

**T:**
> Microservices would demonstrate more distributed systems skills, but I needed to evaluate whether it was the right architectural choice for this project.

**A:**
> I evaluated both approaches against three criteria: **team size, domain stability, and operational complexity**.
>
> - **Team size**: I was the sole developer. Microservices require coordination overhead — service discovery, API versioning, distributed tracing — that doesn't pay off with a single developer.
> - **Domain stability**: The domain boundaries were still evolving. Splitting too early risks getting the service boundaries wrong, which is expensive to fix.
> - **Operational complexity**: Each microservice needs its own CI/CD pipeline, health checks, and monitoring. That would multiply my ops burden without adding user value.
>
> I followed Martin Fowler's advice: **"start with a monolith, split when you feel the pain."** I structured the code by domain — separate controllers, services, and repositories for User, Product, Order, Payment — so the boundaries are clear and ready to split when needed.

**R:**
> The monolith allowed me to deliver faster and focus on the **distributed components that actually matter** — async messaging with RocketMQ, caching with Redis, search with ElasticSearch, and database sharding with ShardingSphere. I learned that good architecture isn't about using the most complex approach — it's about choosing the **right tool for the constraints**.

### Why this story is good:
- **Deliberate decision, not forced compromise** — "I evaluated" 唔係 "I couldn't"
- **Clear criteria** — team size, domain stability, operational complexity
- **Shows maturity** — 唔係跟 trend，而係根據 constraints 選擇
- **Name-drops authority** — Martin Fowler's monolith-first approach

---

## Framing Tips

### ❌ Sounds like excuse

```
"I wanted to do X but couldn't..."
"Only myself doing it so..."
"Just left it as Y for now..."
```

### ✅ Sounds like engineering decision

```
"I evaluated both approaches against three criteria..."
"I followed the monolith-first principle because..."
"I structured the code so future migration is straightforward..."
```

同一件事，frame 成 **deliberate decision** 而唔係 **forced compromise**。

---

## Common Behavioral Questions Checklist

<details>
<summary>Technical challenges</summary>

- "Most challenging bug?" → Story A (ShardingSphere)
- "Complex infrastructure issue?" → Story B (RocketMQ)
- "Improved code quality / process?" → Silent 500 → added log.error, improved observability
</details>

<details>
<summary>Decision-making</summary>

- "Difficult trade-off?" → Story C (Monolith vs Microservices)
- "Disagree with common approach?" → Chose monolith over trendy microservices
- "How do you prioritize?" → Fixed sharding/checkout issues before adding new features (Lesson 20)
</details>

<details>
<summary>Learning & growth</summary>

- "What did you learn recently?" → Distributed systems: transaction messages, cache consistency, sharding
- "Biggest mistake?" → GlobalExceptionHandler without logging — learned observability is non-negotiable
- "How do you stay current?" → Built production-grade features (Redis, MQ, ES, Sharding) hands-on
</details>

<details>
<summary>Teamwork & communication (adapt for solo project)</summary>

- "How do you handle code reviews?" → Set up CI pipeline with automated tests, followed PR workflow
- "How do you document decisions?" → Lesson notes documenting every design decision and trade-off
- "How do you ensure code quality?" → TDD approach, 80%+ coverage, GitHub Actions CI checks
</details>
