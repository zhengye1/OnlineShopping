# Lesson 21: System Design Review — Architecture Walkthrough

## 面試框架：點樣 Present 你嘅 System

```
1. Tech Stack Justification — 點解揀呢啲技術
2. Core Request Flow — trace a request end-to-end
3. Caching Strategy — 點樣減少 DB 壓力
4. Security — Authentication + Authorization
5. Scalability — 由外到內嘅防護層
6. Failure Handling — partial failure 點處理
```

---

## 1. Tech Stack Justification

| 技術 | 點解揀 | Trade-off |
|---|---|---|
| **Spring Boot** | Auto-configuration, embedded server, mature ecosystem | 比 plain Spring 重，但 convention over configuration 省好多 boilerplate |
| **MySQL** | E-commerce data 係 relational（user→order→item→product）。ACID transaction 保證金錢操作一致性 | Scale write 需要 sharding，比 NoSQL 複雜 |
| **Redis** | In-memory，microsecond latency。用做 cache + distributed lock + rate limiting | 數據唔持久（雖然有 RDB/AOF），唔適合做 primary storage |
| **RocketMQ** | 原生支援 **Transaction Message**（HALF→local TX→COMMIT/ROLLBACK），唔使自己實現 Outbox Pattern | 比 Kafka 生態細，但 transaction message 係 killer feature |
| **ElasticSearch** | Inverted index 做 full-text search，支援 relevance scoring 同 CJK tokenization | MySQL LIKE 冇 relevance ranking，大數據量下慢 |
| **Sentinel** | Flow control + circuit breaking，防止 cascading failure | Rules in-memory（production 用 Nacos 持久化） |
| **ShardingSphere** | Transparent sharding，application code 唔使改 | 配置複雜，cross-shard JOIN 有限制 |

**金句：錢 CP，畫面 AP。**
- 涉及金錢嘅操作（checkout, payment）→ 強一致性（CP）
- 瀏覽類操作（product listing）→ 可以容忍短暫 stale data（AP）

---

## 2. Checkout Flow（End-to-End）

```
Client                    Server                         RocketMQ              MySQL
  |                         |                               |                    |
  |-- POST /checkout ------>|                               |                    |
  |   (Bearer JWT)          |                               |                    |
  |                         |-- Send HALF message --------->|                    |
  |                         |                               |                    |
  |                         |<-- Execute local TX ----------|                    |
  |                         |   1. Validate user            |                    |
  |                         |   2. Load cart items          |                    |
  |                         |   3. Redis lock (per product) |                    |
  |                         |   4. Check & deduct stock ----|-----> UPDATE ----->|
  |                         |   5. Price snapshot           |                    |
  |                         |   6. Calculate total + HST 13%|                    |
  |                         |   7. Create order (PENDING)---|-----> INSERT ----->|
  |                         |   8. Clear cart               |                    |
  |                         |   9. Release Redis lock       |                    |
  |                         |                               |                    |
  |                         |-- Return COMMIT ------------->|                    |
  |                         |                               |-- Deliver to ------>
  |                         |                               |   consumer         |
  |<-- 202 Accepted --------|                               |                    |
  |                         |                               |                    |
  |-- Poll order status --->|                               |                    |
```

**點解 202 唔係 201？**
- 201 = 資源已建立完成
- 202 = Request accepted，正在處理中（async）

**Price Snapshot：** OrderItem 儲嘅係 checkout 當刻嘅價錢，唔係 reference product 嘅現價。確保 seller 之後改價唔影響已下單嘅金額。

---

## 3. Caching Strategy — Cache-Aside Pattern

### Read Path
```
Request → Check Redis → Hit → Return cached data
                      → Miss → Read MySQL → Write Redis (TTL) → Return
```

### Write Path (Update/Delete Product)
```
Request → Update/Delete MySQL → Delete Redis cache
```

### 點解 Delete 唔係 Update Cache？

```
Concurrent update 會有 race condition：
T1: User A update → $80 (DB)
T2: User B update → $90 (DB)
T3: User B update cache → $90
T4: User A update cache → $80  ← 舊值覆蓋新值！

Delete 係 idempotent — 唔怕 ordering 問題。
```

### TTL 設計

| Data | TTL | 原因 |
|---|---|---|
| Product detail | 30 min | 單品變動少，cache hit rate 高 |
| Product list | 5 min | 列表更新頻繁（新品、價格變動） |

---

## 4. Security — JWT + RBAC

### JWT Flow
```
Login → Server validates (BCrypt) → Generate JWT (username, role, expiry)
     → Client stores token
     → Every request: Authorization: Bearer <token>
     → JwtAuthenticationFilter → validate → set SecurityContext
```

**Stateless：** Server 唔儲 session，任何 instance 都可以驗證 token。水平 scaling 友好。

### Role-Based Access Control

| Endpoint | Access | 原因 |
|---|---|---|
| `/auth/**` | permitAll | 註冊登入 |
| `GET /products/**` | permitAll | 未登入可瀏覽 |
| `DELETE /products/{id}` | ADMIN only | 防止誤刪 |
| `/api/cart/**`, `/api/orders/**` | authenticated | 需要知道 user identity |
| `/actuator/**` | permitAll | ALB health check 冇 JWT |
| `/api/payments/callback` | permitAll | External payment provider 冇 JWT |

### JWT Weakness & Production 做法

Pure JWT 係 stateless → **做唔到即時 invalidation**。

| 方案 | 做法 |
|---|---|
| **Short-lived token** | Access token 15-30 min + Refresh token 7-14 days |
| **Token blacklist** | Revoked token 放 Redis，每個 request check |
| **Refresh token rotation** | 用完即換，防止 replay attack |

---

## 5. Scalability — 由外到內嘅防護層

```
Internet → Rate Limit → Circuit Breaker → App (stateless) → Cache → DB (sharded)
```

| 層 | Implementation | 解決咩問題 |
|---|---|---|
| **Rate Limit** | Redis INCR + EXPIRE (30 req/60s per IP) | 防 DDoS / 濫用 |
| **Circuit Breaker** | Sentinel (slow ratio 50%, 10s window) | 防 cascading failure |
| **App Layer** | Stateless JWT + ALB | 水平加 instance |
| **Cache** | Redis Cache-Aside | 擋 80%+ read 流量 |
| **DB Sharding** | ShardingSphere (orders by user_id % 2) | 分散 write 壓力 |

### Shard Key 選擇

> Orders sharded by `user_id` — 因為 orders 最常見嘅 query pattern 係「用戶查自己嘅訂單」，user_id sharding 保證 single-shard query。

### 點解 App Layer 可以輕易 Scale？

> JWT stateless = 冇 session 同步問題。加一個 ECS Task，ALB 自動 route traffic 過去。

---

## 6. Failure Handling

### RocketMQ Transaction Message 嘅 3 個 Scenario

| Scenario | 發生咩事 | 結果 |
|---|---|---|
| Local TX 成功 | doCheckout() 完成 → return COMMIT | Message delivered to consumer ✅ |
| Local TX 失敗 | Stock 不足 → exception → return ROLLBACK | Message discarded, DB rolled back ✅ |
| App crash | 冇 COMMIT 亦冇 ROLLBACK | RocketMQ call `checkLocalTransaction()` → check DB → 自動決定 |

### Saga — Compensating Transaction

Cancel order = 補償交易：
```
正向：deduct stock → create order → clear cart
補償：restore stock → set CANCELLED → (cart 已清，唔使還原)
```

SagaExecution + SagaStepLog = **Audit trail**，唔係自動 rollback engine。Production 用嚟 debug partial failure。

### Redis Down — Graceful Degradation

| Redis 功能 | Down 咗點算 |
|---|---|
| Cache | Cache miss → fallthrough 到 MySQL（DB 壓力增加但系統仲 work） |
| Distributed Lock | Lock 失敗 → exception → transaction rollback → user retry |
| Rate Limiting | Filter 失敗 → 視乎實現，可以 fail-open（放行）或 fail-close（拒絕） |

**關鍵詞：Graceful Degradation** — 部分功能降級，但系統唔會完全死。

---

## 面試模擬練習（8 Rounds）

### Round 1: Tech Stack Justification

**Q: Why did you choose this tech stack? Walk me through your key technology decisions and the trade-offs.**

> We built this on **Spring Boot** for its auto-configuration and mature ecosystem — it significantly reduces boilerplate compared to plain Spring. For the database, we chose **MySQL** because e-commerce data is inherently relational — users place orders, orders contain items, items reference products — and we need **ACID transactions** for financial operations. **When money is involved, we need strong consistency (CP).**
>
> **Redis** serves three purposes: caching (product detail 30min TTL), distributed locks (checkout stock deduction), and rate limiting (30 req/60s per IP). We chose **RocketMQ** over Kafka because RocketMQ natively supports **transaction messages** — HALF message → local transaction → COMMIT/ROLLBACK — which guarantees consistency between DB operations and message delivery without implementing the Outbox pattern manually. **ElasticSearch** handles full-text product search with inverted index and relevance scoring, which MySQL LIKE queries can't provide at scale.

### Round 2: Checkout Flow

**Q: When a user clicks 'checkout', what happens end-to-end?**

> 1. Client sends `POST /api/orders/checkout` with JWT Bearer token
> 2. Controller calls `OrderService.checkout()`, which sends a **HALF message** to RocketMQ
> 3. `TransactionListener.executeLocalTransaction()` runs `doCheckout()`:
>    - Validate user is active
>    - Load cart items
>    - For each item: acquire **Redis distributed lock** (per product ID) → check stock ≥ quantity → deduct stock in MySQL → release lock
>    - **Snapshot** current price into OrderItem (not a reference — seller changing price later won't affect this order)
>    - Calculate total + HST 13%
>    - Create Order with `PENDING_PAYMENT` status
>    - Clear cart
> 4. Local TX succeeds → return **COMMIT** → message delivered to consumer
> 5. Controller returns **202 Accepted** (not 201, because the order is created asynchronously)
> 6. Frontend polls order status periodically

### Round 3: Caching Strategy

**Q: How do you keep the cache consistent with the database? What happens when a seller updates a product's price — does the buyer see stale data?**

> We use **Cache-Aside pattern**. On read, check cache first; on miss, load from DB and populate cache with 30-min TTL. On write (update/delete), we **invalidate the cache** immediately. So stale data only exists in a very small race condition window. And even if stale data is served during browsing, **checkout always reads from the database** for price snapshot, so financial accuracy is guaranteed.
>
> We **delete** rather than update the cache because concurrent writes can cause ordering issues — a slower update could overwrite a newer value, leaving the cache permanently inconsistent. Deletion is **idempotent** and guarantees the next read fetches the latest value from the database.

### Round 4: Security

**Q: How does your system handle authentication and authorization?**

> We use **JWT** for stateless authentication. On login, the server validates credentials with **BCrypt** hash comparison and issues a JWT containing username, role, and expiry. Every subsequent request carries the token in the `Authorization: Bearer` header. A `JwtAuthenticationFilter` extracts and validates the token, then sets the `SecurityContext`. Because JWT is stateless, the server doesn't store sessions — any instance can validate the request, making it **horizontally scalable**.
>
> For authorization, we use **Role-Based Access Control** — ADMIN-only for destructive operations like product deletion, authenticated for cart/order operations, and permitAll for public endpoints (browse products, register, health check, payment callback).

**Q: What if a JWT token is stolen?**

> Pure JWT can't do instant invalidation — that's the trade-off of statelessness. The production approach is **short-lived access tokens (15-30 min)** paired with **refresh tokens (7-14 days)**. If compromised, revoke the refresh token and the damage window is limited to the access token's remaining TTL. For immediate revocation, maintain a **token blacklist in Redis** — partially breaks statelessness but is an acceptable trade-off.

### Round 5: Scalability & Failure Handling

**Q: How would you scale this system? What's the first bottleneck?**

> The first bottleneck would be the **database** — reads and writes both hit it. Our scaling strategy works in layers: **Rate limiting** at the entry point rejects abusive traffic. **Redis caching** absorbs most read traffic. For writes, we **shard the orders table by user_id**, distributing write pressure across multiple database instances. The application layer is **stateless** thanks to JWT, so we can horizontally scale by adding instances behind a load balancer. And **circuit breaking** with Sentinel prevents any slow dependency from cascading into a full system outage.

**Q: What happens if RocketMQ goes down during checkout? Or Redis is unavailable?**

> We handle failures at multiple levels. For checkout, we use RocketMQ's **transaction message** — if the local transaction fails, the message is rolled back and never delivered. If the app crashes mid-transaction, RocketMQ calls `checkLocalTransaction()` to verify the DB state and decides COMMIT or ROLLBACK automatically. For order cancellation, we apply the **Saga pattern** with compensating transactions — restore stock, update status. We maintain **saga logs** as an audit trail for debugging partial failures. If Redis goes down, we degrade gracefully — cache misses fall through to MySQL, and lock failures cause retries rather than silent data corruption. The keyword is **graceful degradation**.

### Round 6: Database Design

**Q: Walk me through your database schema. How did you design the relationships?**

> We have 5 core entities. **User** has username, password (BCrypt hashed), email, role, is_active. **Category** has name and a self-referencing `parent_id` for hierarchical categories (e.g., Electronics → Phones). **Product** belongs to a Category and a User (seller), with name, description, price, stock, image URL, status.
>
> **Order** and **OrderItem** are separated because an order can contain multiple products — this is a **One-to-Many** relationship. Putting items directly in the Order table would violate **First Normal Form** (variable number of columns). More importantly, OrderItem serves as a **join table** for the Many-to-Many relationship between Product and Order, while carrying extra attributes: quantity and **snapshot price**.
>
> Product and Order have no direct foreign key — OrderItem bridges them. This decouples order history from product lifecycle. If a product is deleted or price changes, existing orders remain intact with their original snapshot data.

**ER Relationships:**
```
User ──1:N──> Product      (seller lists products)
User ──1:N──> Order        (buyer places orders)
Category ──1:N──> Product   (category contains products)
Category ──1:N──> Category  (self-ref: parent-child hierarchy)
Order ──1:N──> OrderItem    (order contains items)
Product ──1:N──> OrderItem  (product appears in items)
Order ──1:N──> Payment      (multiple payment attempts allowed)
```

### Round 7: Monitoring & Debugging

**Q: It's 3 AM. Users report 500 errors on checkout. How do you debug this?**

> First, I'd **scope the problem** — check `/actuator/health` to see if any dependency is down, and check our custom metrics (`orders.checkout.count`) to understand the failure rate and when it started.
>
> Then I'd go to **CloudWatch logs**, filter by `[ERROR]`, and use the **MDC requestId** to trace the full request flow of a failing checkout. The stack trace from our `GlobalExceptionHandler` — which we made sure always logs exceptions — would point me to the root cause.
>
> Common suspects: database connection timeout, Redis unavailability, or RocketMQ broker issues. The health check endpoint tells me which component is down immediately.
>
> After fixing, I'd verify: error rate drops, `orders.checkout.count` resumes incrementing, `/actuator/health` returns all UP.

**Structured Debugging Framework:**
```
Step 1: Scope     → /actuator/health + metrics → 全掛 or 偶發？
Step 2: Find      → CloudWatch [ERROR] + reqId → stack trace
Step 3: Identify  → Stack trace 指向邊個 component？
Step 4: Check     → /actuator/health → db/redis/es UP or DOWN？
Step 5: Fix       → 修復 + verify error rate + health
```

### Round 8: Deployment & CI/CD

**Q: How do you deploy your application? How do you achieve zero-downtime deployment?**

> Our CI/CD has two stages. **CI** runs on every PR via GitHub Actions — checks out code, sets up Java 21 with Maven cache, runs unit tests with H2 in-memory database (no external dependencies needed), and reports status on the PR. After code review and merge, **CD** is manually triggered via `workflow_dispatch` — this gives us a safety gate before production rollout.
>
> The deploy workflow: configure AWS credentials → login to ECR → build a **multi-stage Docker image** (Maven build stage + slim JRE runtime stage, ~200MB vs ~800MB) → tag with **git SHA** for traceability → push to ECR → update ECS service.
>
> ECS performs a **rolling update** for zero-downtime: new tasks start alongside old ones, ALB health checks verify them via `/actuator/health`, and only when new tasks are healthy does it drain traffic from old tasks and terminate them. At no point are there zero healthy tasks serving traffic.

**CI/CD Pipeline:**
```
PR created → ci.yml: checkout → Java 21 → mvn test (H2) → ✅/❌ status
Code review → merge to main
Manual trigger → deploy.yml:
  1. Configure AWS credentials
  2. Login ECR
  3. Docker multi-stage build
  4. Tag with git SHA
  5. Push to ECR
  6. Update ECS → Rolling update → Zero downtime
```

**Zero-Downtime Rolling Update:**
```
1. ECS starts NEW tasks (new image)
2. ALB health check: GET /actuator/health → 200?
3. New tasks healthy → ALB routes traffic to new tasks
4. Old tasks: drain connections → terminate
→ At no point are there zero healthy tasks
```

---

## Interview Quick-Fire

<details>
<summary>Q1: 你個系統最大嘅 weakness 係咩？</summary>

1. **Single point of failure**: 目前 Redis / RocketMQ 都係單 instance，production 需要 cluster mode
2. **JWT 冇即時 revocation**: Access token expiry 1 day 太長，應該改 15-30 min + refresh token
3. **Sentinel rules in-memory**: 重啟就冇，production 要接 Nacos 持久化
4. **冇 API Gateway**: Rate limiting 做咗喺 app layer，production 應該用 API Gateway（e.g., Kong, AWS API Gateway）統一管理
</details>

<details>
<summary>Q2: 如果 DAU 從 1K 去到 1M，你會改咩？</summary>

1. **Read replica** for MySQL — 讀寫分離
2. **Redis Cluster** — 唔係 single instance
3. **RocketMQ Cluster** — multi-broker
4. **CDN** for static assets（product images）
5. **更多 shard** — user_id % 2 改做 consistent hashing
6. **API Gateway** — 統一 rate limiting, auth, routing
7. **Microservices** — 如果 monolith 已經到瓶頸，拆 Product / Order / Payment service
</details>

<details>
<summary>Q3: 點解用 monolith 唔用 microservices？</summary>

> 呢個 project 規模用 monolith 係正確選擇。Microservices 引入 network latency、distributed tracing、service discovery、data consistency 等複雜度。對於一個 team size 細、domain 未完全穩定嘅系統，monolith 嘅 development velocity 快好多。但我哋嘅 code 已經按 domain 分層（controller/service/repository），之後需要拆嘅時候 boundary 已經清楚。

關鍵：**Monolith-first approach** — Martin Fowler 嘅建議。
</details>

<details>
<summary>Q4: 你喺呢個 project 學到最重要嘅一課係咩？</summary>

因人而異，但好嘅答案：
- **Observability 嘅重要性**：GlobalExceptionHandler 冇 log → production debug 會好痛苦（Lesson 20）
- **EAGER fetch 嘅陷阱**：@ManyToOne 預設 EAGER，sharding 環境下直接 crash（Lesson 20）
- **Configuration 嘅 trade-off**：ShardingSphere 版本地獄、broker IP、H2 scope — 真正嘅工程好多時間花喺 config 唔係 code
</details>

<details>
<summary>Q5: 你點樣保證 checkout 唔會 oversell？</summary>

> Three layers of protection:
> 1. **Redis distributed lock** (per product ID) — 防止 concurrent deduction
> 2. **Stock check before deduction** — verify stock ≥ quantity
> 3. **@Transactional** — 如果任何步驟失敗，整個 transaction rollback
>
> Lock granularity 係 per product ID，唔係 global lock，所以唔同 product 嘅 checkout 可以 parallel 執行。
</details>
