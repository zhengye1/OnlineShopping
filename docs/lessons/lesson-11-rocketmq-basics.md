# Lesson 11: RocketMQ Basics

**Date:** 2026-04-04

---

## 核心概念

### 為什麼需要 Message Queue？

同步處理嘅問題：

```
無 MQ：User → Checkout → Save DB → Send Email → Notify Warehouse → Calculate Points → Response
       （用戶等 1300ms）

有 MQ：User → Checkout → Save DB → Send Message → Response（200ms）
                                       ↓
                                   Message Queue
                                  ↙    ↓     ↘
                            Email   倉庫    積分（各自異步處理）
```

### MQ 三大好處

| 好處 | 解釋 |
|------|------|
| **異步（Async）** | 用戶唔使等非核心操作完成，response 更快 |
| **解耦（Decoupling）** | Email service 掛咗？Checkout 照常運作。Message 留喺 queue，恢復後繼續處理 |
| **削峰（Peak Shaving）** | 雙11 一秒 10 萬 order，放入 queue 做 buffer，Consumer 按能力慢慢消費，DB 唔會被打爆 |

---

## MQ 選型比較（面試重點）

| | **RabbitMQ** | **RocketMQ** | **Kafka** |
|---|---|---|---|
| 出身 | Erlang，歐美主流 | Java，阿里巴巴開源 | Scala，LinkedIn 開源 |
| 適合場景 | 中小規模，功能豐富 | 電商/交易，高可靠 | 大數據/日誌，超高吞吐 |
| 吞吐量 | 萬級/秒 | 十萬級/秒 | 百萬級/秒 |
| 延遲 | 微秒級（最低） | 毫秒級 | 毫秒級 |
| 事務消息 | ❌ | ✅（原生支持） | ❌（需自己實現） |
| 語言生態 | 多語言友好 | Java 生態最好 | 多語言 |

> 面試答法："For an e-commerce system with transactional requirements, I'd choose RocketMQ for its native transaction message support. For big data/log streaming, Kafka. For smaller-scale systems needing rich routing, RabbitMQ."

### 我哋揀 RocketMQ 嘅原因
1. Java 生態 — Spring Boot 項目
2. 事務消息 — 電商場景需要 DB + Message 一致性
3. 電商實戰驗證 — 阿里雙11 用佢扛過

---

## RocketMQ 架構

```
Producer → NameServer（註冊中心）→ Broker（存 message）→ Consumer

Producer: 發消息嘅人（OrderService）
NameServer: 電話簿，Producer/Consumer 透過佢搵到 Broker
Broker: 真正存 message 嘅地方
Consumer: 收消息嘅人（OrderMessageConsumer）
Topic: 消息分類（order-topic, payment-topic 等）
```

---

## Docker 部署

### docker-compose.yml

```yaml
version: '3.8'
services:
  namesrv:
    image: apache/rocketmq:5.3.1
    ports:
      - "9876:9876"
    command: sh mqnamesrv

  broker:
    image: apache/rocketmq:5.3.1
    ports:
      - "10911:10911"
      - "10909:10909"
    depends_on:
      - namesrv
    command: sh mqbroker -n namesrv:9876 --enable-proxy -c /path/to/broker.conf
    volumes:
      - ./broker.conf:/home/rocketmq/rocketmq-5.3.1/conf/broker.conf
```

### broker.conf（重要！）

```properties
brokerIP1 = host.docker.internal
```

**點解需要？** Broker 喺 Docker 入面用 container 內部 IP 註冊到 NameServer。Host 上嘅 Spring Boot 搵唔到呢個內部 IP → connection timeout。設 `host.docker.internal` 令 Broker advertise host machine 嘅 IP。

---

## Spring Boot 整合

### Dependencies

```xml
<dependency>
    <groupId>org.apache.rocketmq</groupId>
    <artifactId>rocketmq-spring-boot-starter</artifactId>
    <version>2.3.1</version>
</dependency>
```

### application.properties

```properties
rocketmq.name-server=localhost:9876
rocketmq.producer.group=online-shopping-producer
```

### Message DTO

```java
@Data
public class OrderEvent {
    private Long orderId;
    private String orderStatus;
    private Long userId;
}
```

**設計決策：Message 帶 ID + 關鍵資訊**

| 做法 | 優點 | 缺點 |
|------|------|------|
| 帶齊所有資料 | Consumer 唔使查 DB | Message 大，耦合高 |
| 只帶 ID | Message 小，解耦 | Consumer 要查 DB |
| **帶 ID + 關鍵資訊（推薦）** | 平衡 | — |

### Producer

```java
@Service
public class OrderMessageProducer {
    private final RocketMQTemplate rocketMQTemplate;

    public void sendOrderMessage(OrderEvent event) {
        rocketMQTemplate.convertAndSend("order-topic", event);
    }
}
```

### Consumer

```java
@Service
@RocketMQMessageListener(topic = "order-topic", consumerGroup = "order-consumer-group")
public class OrderMessageConsumer implements RocketMQListener<OrderEvent> {

    private static final Logger log = LoggerFactory.getLogger(OrderMessageConsumer.class);

    @Override
    public void onMessage(OrderEvent event) {
        log.info("Received order message, order event: {}", event);
    }
}
```

### 喺 OrderService 發消息

```java
// checkout() 最尾
this.orderRepository.save(order);
this.cartItemRepository.deleteByUserId(user.getId());

OrderEvent event = new OrderEvent();
event.setOrderId(order.getId());
event.setOrderStatus(order.getOrderStatus().name());
event.setUserId(user.getId());
this.orderMessageProducer.sendOrderMessage(event);
```

---

## Logger（SLF4J）

生產環境唔用 `System.out.println`，用 SLF4J Logger：

```java
private static final Logger log = LoggerFactory.getLogger(OrderMessageConsumer.class);

log.info("Received order message, orderId: {}", orderId);
```

好處：
- 有 timestamp、class name、level
- 可以配置輸出去 file
- 可以控制 level（DEBUG/INFO/WARN/ERROR）

Spring Boot 已經包含 SLF4J（在 spring-boot-starter-web 入面），唔使額外加 dependency。

---

## 消息可靠性（面試重點）

### Consumer 失敗點算？

```
Consumer 收到 message → 處理成功 → ACK（確認消費）→ message 刪除
Consumer 收到 message → 處理失敗 → 無 ACK → RocketMQ 重新投遞（retry）
```

- 默認重試 **16 次**，間隔越來越長（10s, 30s, 1min, 2min...）
- 16 次都失敗 → 放入 **Dead Letter Queue（死信隊列）** → 人手處理

### 冪等性（Idempotency）

因為有 retry，Consumer 必須係冪等嘅 — 同一個 message 處理 N 次 = 處理 1 次。

```java
// 例：發 email
// 收到 orderId: 10 → check 呢個 order 係咪已經發過 email
// 如果已發 → skip
// 如果未發 → 發 email → 標記已發
```

同 Lesson 8 Payment callback 嘅冪等性同一個概念。

### DB + Message 一致性問題

```java
this.orderRepository.save(order);                    // ✅ DB 成功
this.orderMessageProducer.sendOrderMessage(event);   // ❌ MQ 失敗
// 結果：Order 存咗但 message 無發出 — 不一致！
```

解決方案：**Transaction Message（事務消息）** — Lesson 12 內容。

---

## 踩過的坑

### 1. Docker Broker connection timeout
**現象：** `RemotingTooMuchRequestException: sendDefaultImpl call timeout`
**原因：** Broker 用 container 內部 IP 註冊，Host 上嘅 Spring Boot 搵唔到
**Fix：** `broker.conf` 加 `brokerIP1 = host.docker.internal`

### 2. 改完 Docker config 但仲係 timeout
**現象：** 更新咗 docker-compose + broker.conf，重啟 container，但仲係 timeout
**原因：** Spring Boot 無 restart，RocketMQ producer 啟動時就建立 connection，唔會自動 reconnect
**Fix：** Restart Spring Boot

### 3. SLF4J import 問題
**現象：** IDE 唔識 auto-import Logger
**原因：** IDE 需要手動 import
**Fix：** `import org.slf4j.Logger;` + `import org.slf4j.LoggerFactory;`

---

## 面試題自測

1. **「為什麼需要 Message Queue？」** — 異步（快）、解耦（穩）、削峰（扛得住）
2. **「RocketMQ vs Kafka vs RabbitMQ 怎麼選？」** — 電商用 RocketMQ（事務消息），大數據用 Kafka（高吞吐），中小系統用 RabbitMQ
3. **「Consumer 處理失敗怎麼辦？」** — 自動重試 16 次，之後進死信隊列
4. **「為什麼 Consumer 要冪等？」** — 因為有 retry，同一個 message 可能被消費多次
5. **「DB 操作和消息發送如何保持一致？」** — Transaction Message（半消息 → 本地事務 → commit/rollback）
6. **「NameServer 的作用是什麼？」** — 註冊中心，Producer/Consumer 透過佢發現 Broker 地址

---

## 下課預告：Lesson 12 — RocketMQ Advanced
- Transaction Message（事務消息）實現
- Dead Letter Queue（死信隊列）處理
- 消息順序性保證
- 消息過濾（Tag）
