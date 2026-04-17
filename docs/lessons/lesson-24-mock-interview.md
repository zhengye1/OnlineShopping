# Lesson 24: Final Review & Mock Interview

## Mock Interview Results

### Overall Score: Acceptable — Ready with targeted practice

| Round | Topic | Score | Notes |
|---|---|---|---|
| 1 | Self Introduction | ⚠️ Needs Work | Too generic, need specific tech keywords |
| 2 | Project Walkthrough | ✅ Acceptable | Cover technical components, not just user stories |
| 3 | System Design (MQ) | ⚠️ Needs Work | Distinguish async vs consistency |
| 4 | OOD / Strategy Pattern | ✅✅ Strong | Clear interface → concrete → map → runtime selection |
| 5 | Behavioral (STAR) | ✅✅ Strong | Multi-layer debugging, root cause analysis |
| 6 | Code Review | ✅ Acceptable | Missed cache invalidation — the #1 issue |
| 7 | Your Questions | ✅ Acceptable | Good process question, add more impactful ones |

---

## Strengths

- **Behavioral stories** — STAR structure clear, good detail and learning
- **Design patterns** — Strategy pattern explanation was excellent
- **Real experience** — Interviewer can tell you actually implemented these features

## Areas to Improve

1. **Self intro** — Replace generic phrases with specific tech keywords
2. **Architecture walkthrough** — Talk about technical components (Redis, MQ, ES), not user stories
3. **Cache awareness** — Always check invalidation when you see cache + DB write
4. **Transaction message** — Separate "why async" from "why transaction message"

---

## Round 1: Self Introduction

### ❌ Common Mistakes

```
"passionate about creating user-friendly, efficient and scalable solutions"
"honed my technical skills and improved my abilities in teamwork"
"bring my diverse skill set and collaborative spirit"
"dynamic and forward-thinking environment"
```

These are filler sentences with zero information. Interviewer hears dozens of these daily.

### ✅ Good Self Intro Structure (60-90 seconds)

```
1. Who I am + years of experience         (1 sentence)
2. What I did at last job                  (2-3 sentences, specific)
3. Side project — technical highlights     (3-4 sentences, this is the hook)
4. Why this company                        (1 sentence, specific to company)
```

### Model Answer

> Hi, I'm Vincent. I'm a backend developer with 8 years of experience, primarily in Java.
>
> In my last role at ITSP, I built middleware that integrated IVR systems with third-party APIs — handling real-time call routing and data transformation.
>
> Outside of work, I've been building a **distributed e-commerce system** from scratch using Spring Boot, MySQL, Redis, RocketMQ, and ElasticSearch. It features **async checkout with transaction messages**, **Redis caching with distributed locks for inventory control**, and **database sharding with ShardingSphere**. I also set up CI/CD with GitHub Actions deploying to AWS ECS.
>
> I'm interested in this role because [specific reason about the company].

**Rule: Specific > Generic. Always.**

---

## Round 2: Project Walkthrough

### ❌ Bad: User Stories

> "User can browse products, add to cart, pay for it..."

### ✅ Good: Technical Architecture

> The system is a Spring Boot monolith structured in MVC layers — controllers, services, repositories — organized by domain: User, Product, Order, Payment.
>
> **Security**: All endpoints secured with JWT stateless authentication. Public endpoints (product browsing, registration) don't require tokens.
>
> **Read path**: Product queries hit Redis cache first (Cache-Aside, 30min TTL). Cache miss falls through to MySQL. Full-text search goes through ElasticSearch with inverted index.
>
> **Write path — checkout**: RocketMQ transaction message — HALF message → local transaction (Redis distributed lock per product ID → stock deduction → price snapshot → order creation) → COMMIT. Returns 202 Accepted.
>
> **Resilience**: Sentinel for circuit breaking and rate limiting (30 req/60s per IP). Orders table sharded by user_id via ShardingSphere.
>
> **Deployment**: Docker containerized, CI via GitHub Actions, deployed to AWS ECS with rolling updates using Actuator health checks.

---

## Round 3: System Design — Why MQ?

### Two separate questions, two separate answers:

**Q: Why use a message queue at all?**

> Checkout involves multiple steps — validate, check stock, deduct stock, snapshot price, create order, clear cart. Doing this synchronously makes the user wait. With MQ, we return **202 Accepted immediately** and process in the background. This improves **user experience** and **system throughput**.

**Q: Why transaction message specifically?**

> The problem: what if we save to DB but the message fails to send? Or send the message but DB fails to commit? We get inconsistency.
>
> Transaction message: send HALF message (consumer can't see it) → execute local transaction → COMMIT or ROLLBACK. Guarantees message and DB operation either both succeed or both fail.

**Q: What if app crashes after HALF message?**

> RocketMQ detects the timeout and calls `checkLocalTransaction()`. This method checks the DB — order exists → COMMIT, doesn't exist → ROLLBACK. **Automatic self-recovery**, no manual intervention needed.

---

## Round 4: Design Patterns

### Strategy Pattern (Strong Answer)

> I implemented a Strategy Pattern for Payment. Created a `PaymentProvider` interface with `createCheckout()` and `verifyCallback()` methods. Concrete classes like `PaypalProvider`, `StripeProvider`, `WeChatPayProvider` each implement their own logic. `PaymentService` registers all providers in a Map, and at runtime selects the correct provider based on the payment method.
>
> This follows the **Open/Closed Principle** — adding Apple Pay only requires a new class, no modification to PaymentService.

### Other Patterns (Quick-fire format: Name + Where + How)

| Pattern | Application |
|---|---|
| **Observer** | RocketMQ consumers subscribe to order topics. Producer doesn't know who's listening |
| **Builder** | JWT token: `Jwts.builder().setSubject().setExpiration().signWith().compact()` |
| **Chain of Responsibility** | Spring Security filter chain: RateLimit → JWT → Auth. Each filter handles or passes |
| **Singleton** | Spring beans are singleton scope by default |
| **Repository** | Spring Data JPA — encapsulate data access behind interface |
| **Facade** | Service layer hides repository complexity from controllers |

---

## Round 5: Behavioral — STAR

### Story A: ShardingSphere Cross-Shard Crash

> **S:** Implementing database sharding with ShardingSphere, splitting orders across two MySQL instances by user_id.
>
> **T:** Checkout endpoint returning 500 errors after sharding setup. Needed to find root cause.
>
> **A:**
> 1. 500 error had **no stack trace** — GlobalExceptionHandler wasn't logging. Added `log.error()`.
> 2. Stack trace showed: "all tables must be in the same compute node" — cross-shard JOIN.
> 3. Traced SQL: `Product.findById()` triggering LEFT JOINs to Category and User on different shards.
> 4. Root cause: `@ManyToOne` defaults to **EAGER fetch** — Hibernate auto-joins related tables.
> 5. Fix: Changed to `FetchType.LAZY`. Created separate Spring Profile for sharding config.
>
> **R:** Checkout worked. Learned: always log exceptions in global handlers; understand ORM default behavior — EAGER fetch causes serious issues in distributed DB environments.

---

## Round 6: Code Review — Cache Pattern

### The "Must-Check" Rule

**When you see cache READ + DB WRITE in the same class, always verify:**

1. ✅ Cache invalidation on write (delete cache after DB update)
2. ✅ TTL on cache set (safety net)
3. ✅ Null handling (don't cache null values)
4. ✅ Constructor injection (not @Autowired field injection)

### Common Cache Bug

```java
// ❌ updateProduct() without cache invalidation
public void updateProduct(Long id, UpdateProductRequest request) {
    Product product = productRepository.findById(id).orElseThrow();
    product.setPrice(request.getPrice());
    productRepository.save(product);
    // Missing: redisTemplate.delete("product:" + id);  ← STALE DATA!
}

// ❌ No TTL — cache lives forever
redisTemplate.opsForValue().set(key, product);

// ✅ Fixed
redisTemplate.opsForValue().set(key, product, 30, TimeUnit.MINUTES);
```

---

## Round 7: Questions to Ask Interviewers

### Good Questions

| Type | Question |
|---|---|
| **Tech** | "What does your tech stack look like? Any major migrations planned?" |
| **Team** | "How is the team structured? How do developers collaborate cross-team?" |
| **Process** | "What does the code review process look like?" |
| **Challenge** | "What's the biggest engineering challenge the team is facing right now?" |
| **Growth** | "What does the onboarding process look like for new engineers?" |

The "biggest challenge" question is strongest — shows you want to **solve problems**, not just show up.

---

## Study Plan

### Daily Practice (15 min)
1. Read Self Intro better version **out loud** once
2. Review 2 rounds from Lesson 21 interview answers
3. Cache code check: "Where's the invalidation? What's the TTL?"

### Before Interview
1. Re-read Lessons 21-24 notes
2. Practice 3 STAR stories out loud with a timer (2 min each)
3. Prepare 3 questions for the interviewer specific to the company

### Key Phrases to Memorize

| Topic | Keywords |
|---|---|
| Cache | Cache-Aside, idempotent delete, TTL safety net |
| MQ | Transaction message, HALF/COMMIT/ROLLBACK, async non-blocking |
| Security | Stateless JWT, RBAC, short-lived access + refresh token |
| Scale | Rate limit → circuit breaker → stateless app → cache → sharding |
| OOD | Strategy + Open/Closed, Chain of Responsibility, Observer |
| Behavioral | Systematic approach, root cause analysis, deliberate decision |

---

## Course Complete! 🎓

### 24 Lessons Summary

**Phase 1: Foundation (Lessons 1-6)**
- Project setup, CRUD, JPA relationships, JWT security, testing, validation

**Phase 2: Distributed Components (Lessons 7-12)**
- Order system, Redis cache, Redis lock + rate limit, RocketMQ, ElasticSearch, transaction messages

**Phase 3: Production Readiness (Lessons 13-16)**
- Payment integration, circuit breaking, database sharding, saga pattern

**Phase 4: Deployment & Operations (Lessons 17-20)**
- CI/CD, Docker, AWS ECS, monitoring & observability

**Phase 5: Interview Prep (Lessons 21-24)**
- System design review, OOD practice, behavioral prep, mock interview
