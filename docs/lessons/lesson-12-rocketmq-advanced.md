# Lesson 12: RocketMQ Advanced

**Date:** 2026-04-05

---

## 核心概念

### 問題：DB + Message 一致性

```java
orderRepository.save(order);                    // ✅ DB 成功
orderMessageProducer.sendOrderMessage(event);   // ❌ MQ 失敗
// 結果：Order 存咗但 message 無發出 — Consumer 永遠收唔到通知
```

`@Transactional` 只管 DB，管唔到外部系統（Redis、MQ、HTTP）。呢個就係**分布式事務**嘅核心難題。

---

## Transaction Message（事務消息）

### 流程

```
Producer              Broker                Consumer
   |                    |                      |
   |-- HALF message --> |  (暫存，唔投遞)       |
   |                    |                      |
   |  executeLocalTransaction()                |
   |  (Save DB...)     |                      |
   |                    |                      |
   |  成功:             |                      |
   |-- COMMIT -------> |  --- 投遞 message --> |
   |                    |                      |
   |  失敗:             |                      |
   |-- ROLLBACK ------> |  (刪除 message)      |
   |                    |                      |
   |  保險機制（crash 後）:|                     |
   |  <-- checkLocalTransaction? --|           |
   |  --> COMMIT/ROLLBACK -------> |           |
```

### 核心保證
- DB 成功 + Message 成功 = 一致 ✅
- DB 失敗 + Message 刪除 = 一致 ✅
- DB 成功 + COMMIT 丟失 → Broker 回查 → 補 COMMIT ✅

---

## 實現

### 1. CheckoutCommand DTO

```java
@Data
public class CheckoutCommand {
    OrderRequest request;
    Long userId;
}
```

**為什麼需要？** Listener 執行本地事務時冇 SecurityContext（唔係 HTTP request thread），需要將 userId 傳過去。

### 2. 架構拆分

**之前（同步）：**
```
Controller → OrderService.checkout()
              ├── 驗證購物車
              ├── 扣庫存 + 建 Order
              ├── Save DB
              ├── 清購物車
              ├── Send Message（可能失敗！）
              └── Return OrderResponse
```

**之後（Transaction Message）：**
```
Controller → OrderService.checkout()
              ├── getCurrentUser()
              ├── 建 CheckoutCommand + OrderEvent
              └── Send HALF Message
                    ↓
              OrderTransactionListener.executeLocalTransaction()
              ├── OrderService.doCheckout()
              │    ├── 驗證購物車
              │    ├── 扣庫存 + 建 Order
              │    ├── Save DB
              │    └── 清購物車
              └── Return COMMIT / ROLLBACK
```

### 3. Producer（改用 sendMessageInTransaction）

```java
public void sendOrderMessage(OrderEvent event, CheckoutCommand command) {
    Message<OrderEvent> message = MessageBuilder.withPayload(event).build();
    rocketMQTemplate.sendMessageInTransaction("order-topic", message, command);
    // command 作為 arg 傳給 Listener
}
```

### 4. TransactionListener

```java
@RocketMQTransactionListener
public class OrderTransactionListener implements RocketMQLocalTransactionListener {

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message message, Object arg) {
        try {
            orderService.doCheckout((CheckoutCommand) arg);
            return RocketMQLocalTransactionState.COMMIT;
        } catch (Exception e) {
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message message) {
        // Broker 回查：check DB 有冇呢筆 order
        // 簡化版：直接 return COMMIT
        return RocketMQLocalTransactionState.COMMIT;
    }
}
```

### 5. Controller（202 Accepted）

```java
@PostMapping("/checkout")
@ResponseStatus(HttpStatus.ACCEPTED)  // 202 — 已接受，處理中
public Map<String, String> checkout(@Valid @RequestBody OrderRequest orderRequest) {
    this.orderService.checkout(orderRequest);
    return Map.of("message", "Order is being processed");
}
```

**為什麼 202 唔係 201？** 因為事務消息係異步嘅，用戶 call checkout 嗰刻 order 可能未真正創建。202 = 「已接受你嘅請求，正在處理」。

---

## 消息可靠性機制

### 重試機制
```
Consumer 收到 message → 處理成功 → ACK → message 刪除
Consumer 收到 message → 處理失敗 → 無 ACK → RocketMQ 重新投遞
```
- 默認重試 16 次，間隔遞增（10s, 30s, 1min, 2min...）
- 16 次都失敗 → Dead Letter Queue（死信隊列）→ 人手處理

### 冪等性（Idempotency）
因為有 retry，Consumer 必須係冪等嘅：
```java
// 收到 orderId: 10 → check 呢個 order 係咪已經處理過
// 已處理 → skip
// 未處理 → 處理 → 標記已處理
```
同 Lesson 8 Payment callback 嘅冪等性概念一樣。

---

## 踩過的坑

### 1. No EntityManager with actual transaction available
**現象：** `executeLocalTransaction` 執行 `doCheckout` 時 `deleteByUserId()` 報錯
**原因：** `doCheckout()` 無 `@Transactional`，delete 操作需要事務
**Fix：** 喺 `doCheckout()` 加 `@Transactional`

```java
@Transactional  // 一定要加！
public Order doCheckout(CheckoutCommand command) { ... }
```

### 2. doCheckout 用 getCurrentUser() → 失敗
**現象：** Listener 執行時 SecurityContext 為空
**原因：** Listener 唔係由 HTTP request thread 執行，冇 SecurityContext
**Fix：** 用 `userRepository.findById(command.getUserId())` 代替 `getCurrentUser()`

### 3. checkout() 唔使 @Transactional
**原因：** checkout() 只發 HALF message，冇 DB 操作。真正嘅 DB 操作喺 doCheckout()

### 4. Producer 參數 type 唔 match
**現象：** `sendOrderMessage(OrderEvent, OrderRequest)` vs `sendOrderMessage(OrderEvent, CheckoutCommand)`
**原因：** 重構後 arg 從 `OrderRequest` 改成 `CheckoutCommand`，但 Producer signature 忘記改
**Fix：** 統一用 `CheckoutCommand`

### 5. OrderEvent orderId 為 null
**現象：** Consumer 收到 `OrderEvent(orderId=null, ...)`
**原因：** HALF message 發送時 order 未 save，所以無 orderId
**解法：** 可接受 — Consumer 用 userId 或其他方式查詢

---

## 面試題自測

1. **「@Transactional 可以保證 DB + MQ 一致性嗎？」** — 不可以，@Transactional 只管 DB 事務，管唔到外部系統
2. **「Transaction Message 的流程是什麼？」** — HALF message → 本地事務 → COMMIT/ROLLBACK → 保險回查
3. **「為什麼需要 checkLocalTransaction？」** — 防止 COMMIT/ROLLBACK 消息丟失（如 server crash），Broker 主動回查確認
4. **「Consumer 為什麼要冪等？」** — 因為有重試機制，同一個 message 可能被消費多次
5. **「Dead Letter Queue 是什麼？」** — 重試 N 次都失敗的 message 會進入死信隊列，等待人工處理
6. **「202 和 201 的區別？」** — 201 Created = 資源已創建；202 Accepted = 請求已接受，還在處理中

---

## 下課預告：Lesson 13 — ElasticSearch Integration
- 全文搜索 vs LIKE 查詢
- ElasticSearch 基本概念（Index, Document, Mapping）
- 商品索引建立
- 搜索優化
