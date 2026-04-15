# Lesson 22: OOD Practice — Object-Oriented Design Interview

## OOD vs System Design

| | System Design | OOD |
|---|---|---|
| **Focus** | 整個系統架構（services, DB, cache, MQ） | Class structure（classes, relationships, patterns） |
| **Output** | Architecture diagram | Class diagram |
| **Example** | "Design Twitter" | "Design a parking lot" |

---

## OOD 面試框架（4 步）

```
Step 1: Clarify    → 問 scope, constraints, edge cases
Step 2: Objects    → 識別 core classes + attributes + methods
Step 3: Relations  → class diagram（has-a, is-a, uses-a）
Step 4: Patterns   → 邊啲 design pattern 適用
```

**關鍵：Objects = Data + Behavior**

```
Database Design:  「Order 有咩 columns？」     → data only
OOD:              「Order 識得做咩？」           → data + behavior
```

---

## Rich Domain Model vs Anemic Domain Model

```java
// ❌ Anemic: Object 只有 getter/setter，logic 全喺 Service
orderService.deductStock(product, qty);

// ✅ Rich: Object 自己管自己嘅 state
product.deductStock(qty);
```

OOD 面試偏好 **Rich Domain Model**。Production 兩種都有人用。

---

## Design Patterns in Our Project

| Pattern | 用喺邊 | 點解 |
|---|---|---|
| **Strategy** | Payment methods（面試答法） | 唔同 payment method 有唔同 implementation，唔使 if-else |
| **Chain of Responsibility** | Filter Chain (RateLimit → JWT → Auth) | 每個 Filter 決定處理定傳畀下一個 |
| **Observer** | RocketMQ Consumer（listen to events） | Consumer subscribe topic，有 message 就 react |
| **Template Method** | `OncePerRequestFilter.doFilterInternal()` | 你 extend 佢，implement 特定步驟 |
| **Repository** | Spring Data JPA repositories | 封裝 data access behind interface |
| **Singleton** | Spring Beans 預設 singleton scope | 一個 class 只有一個 instance |
| **Builder** | `Jwts.builder().setSubject()...` | 一步步建構複雜 object |
| **Facade** | Service layer | Controller 唔直接碰 Repository |
| **Dependency Injection (IoC)** | Constructor injection everywhere | Framework 注入 dependencies，方便 testing |

---

## Key Design Principles

### Open/Closed Principle (OCP)

```java
// ❌ 加新 payment method 要改 PaymentService
if (method == CREDIT_CARD) { ... }
else if (method == PAYPAL) { ... }
else if (method == BANK) { ... }

// ✅ 加新 payment method 只需要加一個 class
public interface PaymentStrategy {
    PaymentResult process(BigDecimal amount);
}
// PaymentService 完全唔使改
```

Open for extension, closed for modification.

### Single Responsibility Principle (SRP)

每個 class 只負責一件事：
- Controller → handle HTTP request/response
- Service → business logic
- Repository → data access

### Strategy Pattern 完整例子

```
                    <<interface>>
                   PaymentStrategy
                  + process(amount)
                  + refund(amount)
                 /        |        \
               /          |          \
CreditCardPayment   PayPalPayment   BankTransferPayment
```

```java
public class PaymentService {
    private final Map<PaymentMethod, PaymentStrategy> strategies;

    public PaymentResult pay(Order order, PaymentMethod method) {
        PaymentStrategy strategy = strategies.get(method);
        return strategy.process(order.getTotalAmount());
    }
}
```

---

## OOD 練習：Notification System

### Requirements

Design a notification system that sends via Email, SMS, and Push. Users set preferred channels. Support urgent (immediate) and batch (daily digest) notifications.

### Step 1: Clarifying Questions

| 方向 | 問題 |
|---|---|
| **Scale** | 幾多 users？每日幾多 notifications？ |
| **Channels** | 將來會唔會加新 channel（WhatsApp, Slack）？ |
| **Preferences** | User 可以 per notification type 設定 channel 嗎？ |
| **Failure** | 發送失敗要 retry 嗎？fallback 去另一個 channel？ |
| **Content** | 唔同 channel 嘅 content format 一唔一樣？ |

### Step 2 & 3: Core Classes + Relationships

```
NotificationService
    |
    |--- uses --→ Notification (user, message, type, status)
    |                  |
    |                  |--- has-a --→ User (email, phone, preferences)
    |                  |--- has-a --→ Message (title, body)
    |
    |--- uses --→ <<NotificationChannel>>
                    /       |        \
              Email       SMS       Push
```

**Classes:**

```
<<interface>> NotificationChannel
  + send(User user, Message message)

EmailChannel implements NotificationChannel
  + send() → call email API

SMSChannel implements NotificationChannel
  + send() → call SMS API

PushChannel implements NotificationChannel
  + send() → call push API

User
  - username, email, phoneNumber
  - preferredChannels: List<NotificationChannel>
  + subscribe(channel)
  + unsubscribe(channel)

Message
  - title, body
  + formatFor(channelType): String    ← SMS 短, Email 長

Notification
  - user, message, type (URGENT / BATCH)
  - status (PENDING / SENT / FAILED)
  - createdAt

NotificationService
  + send(notification)
  + scheduleBatch()
  + retry(notification)
```

### Step 4: Design Patterns

| Pattern | 用喺邊 |
|---|---|
| **Strategy** | NotificationChannel interface + 多個 implementation |
| **Observer** | User subscribe channels |
| **Template Method** | Message.formatFor() 唔同 channel 唔同 format |
| **Singleton** | NotificationService（Spring Bean） |

### Core Logic

```java
public class NotificationService {

    public void send(Notification notification) {
        User user = notification.getUser();

        if (notification.getType() == URGENT) {
            // 即刻 send，用 user 所有 preferred channels
            for (NotificationChannel channel : user.getPreferredChannels()) {
                channel.send(user, notification.getMessage());
            }
        } else {
            // BATCH → 加入 queue，等 scheduler 統一發送
            batchQueue.add(notification);
        }
    }
}
```

### Failure Handling

**Urgent:**
```
1. Send to ALL preferred channels simultaneously
2. Any channel fails → retry with exponential backoff (1s → 2s → 4s)
3. Max retries exceeded → mark FAILED, log for monitoring
4. Other channels likely succeeded → user 大概率收到
```

**Batch:**
```
1. Retry same channel (with delay)
2. Max retries exceeded → fallback to next preferred channel
3. All channels failed → mark FAILED, alert operations team
```

---

## Interview Quick-Fire

<details>
<summary>Q1: Strategy Pattern 同 if-else 有咩分別？幾時應該用？</summary>

Strategy Pattern 將每個 algorithm 封裝成獨立 class，通過 interface 統一調用。好處：加新 strategy 唔使改原有 code（Open/Closed Principle）。用 if-else 的話，每加一個 case 就要改原有 code，違反 OCP。

**幾時用：** 當你有 3+ 個 variants 同一個行為，而且預期會加新 variant。2 個 variant 用 if-else 就夠。
</details>

<details>
<summary>Q2: Rich Domain Model vs Anemic Domain Model？</summary>

**Anemic：** Entity 只有 getter/setter，所有 logic 喺 Service layer。Spring 生態常見，因為 Service + Repository pattern 天然傾向呢個方向。

**Rich：** Entity 自己有 behavior（e.g., `order.cancel()`, `product.deductStock()`）。更符合 OOP 精神，Martin Fowler 推薦。

**Trade-off：** Rich model 嘅 entity 會有 dependencies（e.g., 需要 repository），難做 serialization。Anemic model 簡單直接，但 business logic 散落喺 Service 入面。
</details>

<details>
<summary>Q3: 你 project 用咗邊啲 Design Patterns？</summary>

- **Chain of Responsibility**: Spring Security Filter Chain — RateLimit → JWT → Auth filters
- **Observer**: RocketMQ consumer subscribes to order events
- **Strategy**: Payment methods（可以用 interface + multiple implementations 解釋）
- **Template Method**: OncePerRequestFilter — extend and implement doFilterInternal()
- **Builder**: JWT token builder
- **Repository**: Spring Data JPA — encapsulate data access behind interface
- **Facade**: Service layer hides repository complexity from controllers
- **Singleton**: Spring beans are singleton by default
</details>

<details>
<summary>Q4: Open/Closed Principle 係咩？你點樣 apply？</summary>

Open for extension, closed for modification. 即係加新功能唔使改原有 code。

例子：Payment 用 Strategy Pattern — 加新 payment method（e.g., Apple Pay）只需要建一個新 class implement PaymentStrategy，PaymentService 一行都唔使改。

反例：if-else chain，每加一個 case 都要改 service code，容易引入 bug。
</details>

<details>
<summary>Q5: Dependency Injection 有咩好處？</summary>

1. **Testability** — 可以注入 mock（@Mock + @InjectMocks）
2. **Loose coupling** — class 唔知道具體 implementation，只 depend on interface
3. **Flexibility** — 可以輕易替換 implementation（e.g., 開發用 H2，production 用 MySQL）
4. **Single Responsibility** — class 唔負責 create dependencies，只負責用

Spring 用 constructor injection 實現，IoC container 管理 bean lifecycle。
</details>
