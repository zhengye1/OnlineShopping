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
| "Most challenging bug?" | **Story A**: ShardingSphere cross-shard crash |
| "Debug under pressure?" | **Story B**: RocketMQ broker IP timeout |
| "Difficult trade-off?" | **Story C**: Monolith vs Microservices decision |
| "Time you improved a process?" | Silent 500 fix — 加 log.error 改善 observability |
| "Disagree with a decision?" | 堅持 monolith 而唔係跟 microservices trend |

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
