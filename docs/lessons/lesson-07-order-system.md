# Lesson 7: Order System

**Date:** 2026-03-31

---

## 核心概念

### Checkout Flow（完整流程）

```
Cart → 验证非空 → 逐个验证product状态/库存 → 创建Order + OrderItems
     → snapshot当前价格 → 扣库存 → 计算税 → 清空购物车
```

呢个流程必须用 `@Transactional` — 任何一步失败都要全部rollback。

### Price Snapshot（面试常问）

```java
orderItem.setProductPrice(product.getPrice());  // 锁定下单时的价格
```

Order是不可变的历史记录。如果join Product拎价格，商品加价后order显示就会错。
> "OrderItem stores the price at the time of purchase. The Product table has the current price. They are fundamentally different things."

### Concurrency问题

为什么checkout时要重新验证stock和price，而不是信cart的数据？
> "Between the time you added to cart and the time you checkout, another user might have bought the last item. Always validate at the point of transaction."

### Tax计算（Ontario HST 13%）

```java
long subtotal = order.getTotalPrice();       // 商品总价（cents）
long tax = subtotal * 13 / 100;              // HST 13%
long total = subtotal + tax;                 // 含税总价
```

用 `long`（cents）避免浮点数精度问题：`0.1 + 0.2 ≠ 0.3`。

### 价格单位 — Cents

Backend存cents（99900 = $999.00），前端负责format显示：
```javascript
// Frontend
const formatPrice = (cents) => `$${(cents / 100).toFixed(2)}`;
```
> "Separation of concerns — display logic belongs to the frontend."

---

## 状态机 State Machine（面试常问）

```
PENDING_PAYMENT → PAID          (payment callback)
PENDING_PAYMENT → CANCELLED     (buyer cancel)
PAID            → SHIPPED       (seller ship + tracking number)
PAID            → CANCELLED     (buyer cancel, 需退款)
SHIPPED         → DELIVERED     (物流确认)
DELIVERED       → COMPLETED     (buyer confirm 或 auto 7天)
DELIVERED       → RETURNED      (buyer request return)
COMPLETED       → RETURNED      (buyer request return, 有时限)
```

唔系每个status都可以随便转换。Cancel只允许 `PENDING_PAYMENT` 和 `PAID` 状态。

### Cancel Order — 恢复库存

```java
for (OrderItem item : order.getOrderItems()) {
    item.getProduct().setStock(item.getProduct().getStock() + item.getQuantity());
}
order.setOrderStatus(OrderStatus.CANCELLED);
```

Cancel唔系delete — Order是商业记录，只改状态，唔好物理删除。

---

## Information Hiding（安全设计）

查订单详情时，如果order唔属于当前user：
```java
// WRONG: 返回403 — 泄露order存在
throw new ForbiddenException("No permission");

// CORRECT: 返回404 — 唔泄露任何信息
throw new ResourceNotFoundException("Order not found");
```

> "Same principle as login: 'Invalid username or password' instead of 'Username not found'. Never reveal whether a resource exists to unauthorized users."

---

## `@Transactional` 深入理解

### 为什么checkout需要？

扣库存 + 创建order + 清购物车 = 一个atomic操作。
如果扣咗库存但save order失败 → 库存少咗但无order → data inconsistent。

### 为什么cancel需要？

恢复库存 + 改status = 一个atomic操作。

> "Any operation that modifies multiple entities must be transactional."

---

## Entity → DTO转换

永远唔好将entity直接返畀前端：
- 安全（隐藏password等敏感field）
- 灵活（DTO可以加计算field如tax）
- 解耦（entity结构变唔影响API contract）

```java
private OrderResponse toOrderResponse(Order order) { ... }
private OrderItemResponse toOrderItemResponse(OrderItem orderItem) { ... }
```

### Enum in DTO

DTO用String而唔系enum type：
```java
response.setOrderStatus(order.getOrderStatus().name());  // enum → String
```
> "DTOs face the outside world. Frontend doesn't know your Java enums. Jackson serializes both to the same JSON string, but String is more explicit."

---

## Optional处理

`JpaRepository.findById()` 返回 `Optional<T>`，唔系直接entity：
```java
// WRONG
Order order = orderRepository.findById(id);

// CORRECT
Order order = orderRepository.findById(id)
    .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
```

---

## API Endpoints

```
POST   /api/orders/checkout    → OrderResponse (需login, 201)
GET    /api/orders             → List<OrderResponse> (需login, 我的订单)
GET    /api/orders/{id}        → OrderResponse (需login, ownership check)
PUT    /api/orders/{id}/cancel → OrderResponse (需login, 状态验证)
```

---

## 面试题自测

1. **「Checkout流程需要做哪些验证？为什么？」**
2. **「为什么OrderItem要存productPrice而不是join Product表？」** — price snapshot，不可变历史记录
3. **「@Transactional做什么？如果checkout不加会怎样？」** — atomicity，可能数据不一致
4. **「Order cancel应该delete还是改status？为什么？」** — 改status，order是商业记录
5. **「为什么查不属于自己的order返404而不是403？」** — information hiding
6. **「为什么用long存价格而不是double？」** — 浮点数精度问题
7. **「Order status transition应该怎样设计和enforce？」** — 状态机，service层验证合法转换

---

## 下课预告：Lesson 8 — Payment Integration
- Payment patterns
- Idempotency（幂等性）
- Payment callback handling
