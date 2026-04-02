# Lesson 8: Payment Integration

**Date:** 2026-04-01

---

## 核心概念

### Payment Flow（面试必问）

```
1. Buyer按"Pay" → Backend创建Payment record (PENDING)
2. Backend发request去Payment Provider (Stripe/PayPal)
3. Payment Provider返回payment URL
4. Buyer被redirect去provider页面付款
5. 付款完成 → Provider call你既webhook/callback
6. Backend验证callback → 更新Payment/Order status
```

> "Your backend NEVER handles credit card numbers directly. That requires PCI DSS compliance which is extremely expensive. Always delegate to Stripe/PayPal."

### Webhook vs Polling

Payment provider**主动通知**你结果（webhook），唔系你不断去问。
```
Webhook: Provider → POST /api/payments/callback → 你既backend
Polling: 你既backend → GET /api/stripe/status → Provider (❌ 浪费资源)
```

### Webhook Authentication

Callback endpoint无JWT authentication，因为payment provider无你既token。防伪造方法：

1. **Webhook Signature Verification**（最重要）— Provider用shared secret签名，你验证签名
2. **IP Whitelist** — 只允许provider IP
3. **Idempotency** — 重复call无副作用
4. **Amount Verification** — 金额唔match就reject

> "Webhook signature verification is the primary defense. Combined with IP whitelisting, idempotency checks, and amount validation as defense in depth."

---

## Idempotency 幂等性（面试重点）

同一个request执行1次同执行N次，结果一样。

### 为什么需要？

Payment provider callback可能重复发送（network timeout、retry机制）：
```
Stripe → POST /callback → 你返200 → network断 → Stripe以为失败 → 再POST一次
```

### 实现方式

```java
// 已经SUCCESS就直接return，唔再处理
if (payment.getPaymentStatus() == PaymentStatus.SUCCESS) {
    return toPaymentResponse(payment);  // 无副作用
}
```

> "Idempotent means: if I accidentally call you twice, nothing bad happens. This applies to ALL webhooks, not just payment."

---

## Payment Entity设计

```java
@ManyToOne
@JoinColumn(name = "order_id", nullable = false)
private Order order;  // 一个Order可以有多次payment attempt
```

为什么ManyToOne？
- 第一次付款fail → 创建payment record (FAILED)
- 第二次付款success → 创建另一个payment record (SUCCESS)
- 每个record都系独立既历史记录

---

## Tax计算 — BigDecimal精确rounding

```java
// Long integer division: 999 * 13 / 100 = 129（截断.87）
// BigDecimal: 999 * 13 / 100 = 129.87 → HALF_UP → 130

long tax = BigDecimal.valueOf(subtotal)
        .multiply(BigDecimal.valueOf(13))
        .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
        .longValue();
```

全程用integer运算避免浮点数，BigDecimal只负责rounding。

### 为什么用Long (cents) 而唔系BigDecimal存储？

| | Long (cents) | BigDecimal |
|---|---|---|
| 使用者 | Stripe, Shopify, Amazon | 银行, 金融系统 |
| 运算 | `+` `-` `*` 直接用 | `.add()` `.multiply()` |
| 比较 | `==` / `.equals()` | `.compareTo()` |
| Performance | 快 | 较慢 |
| 精度 | 整数无精度问题 | 任意小数位 |

> "For e-commerce, store prices as integers (cents). For banking, use BigDecimal."

---

## SecurityConfig — 放行外部endpoint

```java
.requestMatchers("/api/payments/callback").permitAll()  // Payment provider无JWT
```

唔系所有endpoint都需要authentication。外部系统callback必须放行，再用其他方式验证。

---

## Repository知识点

### JpaRepository vs CrudRepository

```
JpaRepository extends ListCrudRepository extends CrudRepository
```

JpaRepository = CrudRepository + 分页 + 批量 + flush。无理由用CrudRepository。

### @Repository唔使加

`extends JpaRepository` 既interface会被Spring Data JPA自动扫描注册做bean，唔需要 `@Repository`。

---

## Long比较陷阱

```java
// ❌ Long对象用 != 比较（cache只有-128到127）
if (payment.getAmount() != request.getAmount())

// ✅ 用equals
if (!payment.getAmount().equals(request.getAmount()))
```

---

## API Endpoints

```
POST   /api/payments/{orderId}    → PaymentResponse (需login, 201)
POST   /api/payments/callback     → PaymentResponse (无需login, 200)
```

---

## 面试题自测

1. **「Payment flow系点样？你既backend同Stripe之间点互动？」**
2. **「点解唔直接系你backend处理信用卡信息？」** — PCI DSS compliance成本极高
3. **「Webhook callback endpoint需唔需要authentication？点样防伪造？」** — Signature verification + IP whitelist + idempotency + amount check
4. **「咩系Idempotency？点解payment callback需要？」** — 同一request执行N次结果一样，防network retry重复处理
5. **「为什么Payment同Order系ManyToOne？」** — 一个order可以有多次payment attempt
6. **「Long对象比较用==定equals？」** — equals，因为Long cache只有-128到127
7. **「为什么用cents (long) 存价格？」** — 避免浮点数精度问题

---

## 附录：实际Payment Provider Integration参考

### Dependencies (pom.xml)

```xml
<!-- Stripe -->
<dependency>
    <groupId>com.stripe</groupId>
    <artifactId>stripe-java</artifactId>
    <version>26.0.0</version>
</dependency>

<!-- PayPal -->
<dependency>
    <groupId>com.paypal.sdk</groupId>
    <artifactId>checkout-sdk</artifactId>
    <version>2.0.0</version>
</dependency>
```

### application.properties

```properties
# Stripe
stripe.api-key=${STRIPE_API_KEY}
stripe.webhook-secret=${STRIPE_WEBHOOK_SECRET}

# PayPal
paypal.client-id=${PAYPAL_CLIENT_ID}
paypal.client-secret=${PAYPAL_CLIENT_SECRET}
paypal.mode=sandbox  # sandbox / live
```

### Strategy Pattern — 统一interface

多个payment provider用Strategy Pattern，加新provider唔使改现有代码（Open-Closed Principle）。

```java
public interface PaymentProvider {
    String createCheckout(Order order, long amount);     // 返回payment URL
    boolean verifyCallback(String payload, String signature);  // 验证签名
}
```

### Stripe实现

```java
@Service("stripe")
public class StripePaymentProvider implements PaymentProvider {

    @Value("${stripe.api-key}")
    private String apiKey;

    @Value("${stripe.webhook-secret}")
    private String webhookSecret;

    @Override
    public String createCheckout(Order order, long amount) {
        Stripe.apiKey = apiKey;

        SessionCreateParams params = SessionCreateParams.builder()
            .setMode(SessionCreateParams.Mode.PAYMENT)
            .setSuccessUrl("https://yoursite.com/payment/success?orderId=" + order.getId())
            .setCancelUrl("https://yoursite.com/payment/cancel?orderId=" + order.getId())
            .addLineItem(SessionCreateParams.LineItem.builder()
                .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                    .setCurrency("cad")
                    .setUnitAmount(amount)  // cents
                    .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                        .setName("Order #" + order.getId())
                        .build())
                    .build())
                .setQuantity(1L)
                .build())
            .putMetadata("orderId", order.getId().toString())
            .build();

        Session session = Session.create(params);
        return session.getUrl();  // 前端redirect去呢个URL
    }

    @Override
    public boolean verifyCallback(String payload, String sigHeader) {
        try {
            Webhook.constructEvent(payload, sigHeader, webhookSecret);
            return true;
        } catch (SignatureVerificationException e) {
            return false;
        }
    }
}
```

### PayPal实现

```java
@Service("paypal")
public class PayPalPaymentProvider implements PaymentProvider {

    @Value("${paypal.client-id}")
    private String clientId;

    @Value("${paypal.client-secret}")
    private String clientSecret;

    @Value("${paypal.mode}")
    private String mode;

    private PayPalEnvironment getEnvironment() {
        return "live".equals(mode)
            ? new PayPalEnvironment.Live(clientId, clientSecret)
            : new PayPalEnvironment.Sandbox(clientId, clientSecret);
    }

    @Override
    public String createCheckout(Order order, long amount) {
        PayPalHttpClient client = new PayPalHttpClient(getEnvironment());

        OrdersCreateRequest request = new OrdersCreateRequest();
        request.prefer("return=representation");

        // amount从cents转dollars (PayPal用dollars string)
        String dollarAmount = BigDecimal.valueOf(amount)
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
            .toString();

        OrderRequest orderRequest = new OrderRequest()
            .checkoutPaymentIntent("CAPTURE")
            .purchaseUnits(List.of(
                new PurchaseUnitRequest()
                    .amountWithBreakdown(new AmountWithBreakdown()
                        .currencyCode("CAD")
                        .value(dollarAmount))
                    .referenceId(order.getId().toString())
            ));

        request.requestBody(orderRequest);
        HttpResponse<com.paypal.orders.Order> response = client.execute(request);

        // 搵approval link
        return response.result().links().stream()
            .filter(link -> "approve".equals(link.rel()))
            .findFirst()
            .map(LinkDescription::href)
            .orElseThrow();
    }

    @Override
    public boolean verifyCallback(String payload, String signature) {
        // PayPal webhook signature verification
        // 用PayPal SDK验证
        return true; // 简化示意
    }
}
```

### PaymentService — 动态选择provider

```java
@Service
public class PaymentService {
    private final Map<String, PaymentProvider> providers;

    // Spring自动inject所有PaymentProvider实现
    // key = bean name ("stripe", "paypal")
    public PaymentService(Map<String, PaymentProvider> providers, ...) {
        this.providers = providers;
    }

    public PaymentResponse createPayment(Long orderId, String method) {
        PaymentProvider provider = providers.get(method);
        if (provider == null) throw new BadRequestException("Unsupported payment method");

        // ...验证order状态、计算amount...

        String checkoutUrl = provider.createCheckout(order, amount);
        payment.setPaymentMethod(method);
        // ...save payment, return response with checkoutUrl...
    }
}
```

### Callback endpoints — 每个provider分开

```java
@PostMapping("/callback/stripe")
public void stripeCallback(
        @RequestBody String payload,
        @RequestHeader("Stripe-Signature") String sigHeader) {
    PaymentProvider provider = providers.get("stripe");
    if (!provider.verifyCallback(payload, sigHeader)) {
        throw new BadRequestException("Invalid signature");
    }
    // ...parse event, 更新payment + order status...
}

@PostMapping("/callback/paypal")
public void paypalCallback(
        @RequestBody String payload,
        @RequestHeader("Paypal-Transmission-Sig") String sigHeader) {
    PaymentProvider provider = providers.get("paypal");
    if (!provider.verifyCallback(payload, sigHeader)) {
        throw new BadRequestException("Invalid signature");
    }
    // ...parse event, 更新payment + order status...
}
```

### SecurityConfig放行

```java
.requestMatchers("/api/payments/callback/**").permitAll()
```

### 完整flow总结

```
1. 前端call POST /api/payments/{orderId}?method=stripe
2. Backend创建PENDING payment + call Stripe API
3. Backend返回 { checkoutUrl: "https://checkout.stripe.com/..." }
4. 前端redirect buyer去checkoutUrl
5. Buyer系Stripe页面付款
6. Stripe call POST /api/payments/callback/stripe
7. Backend验证signature → 更新payment SUCCESS → order PAID
8. Buyer被redirect返success page
```

---

## 下课预告：Lesson 9 — Redis Cache
- Redis基础概念
- Spring Data Redis integration
- Cache购物车 + 热门商品
- Cache invalidation策略
