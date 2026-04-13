# Lesson 20: Monitoring & Observability

## 三大支柱

| 支柱 | 解決咩問題 | 工具 |
|---|---|---|
| **Logging** | 發生咗咩事？ | SLF4J + Logback + MDC |
| **Metrics** | 系統狀態點？ | Micrometer + Actuator |
| **Health Check** | App 仲活著嗎？ | Spring Boot Actuator |

---

## Part A: Spring Boot Actuator (Health Check)

### Setup

1. `pom.xml` 加 `spring-boot-starter-actuator`
2. `application.properties`:
```properties
management.endpoints.web.exposure.include=health, info, metrics
management.endpoint.health.show-details=always
```
3. `SecurityConfig` 加 `/actuator/**` permitAll（ALB 唔帶 JWT）

### Health Check Response

```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" },
    "redis": { "status": "UP" },
    "elasticsearch": { "status": "UP", "details": { "status": "yellow" } },
    "sentinel": { "status": "UNKNOWN" },
    "diskSpace": { "status": "UP" }
  }
}
```

**Status 判斷邏輯：**
- 任何 component **DOWN** → overall = **DOWN** → ALB 踢走呢個 Task
- **UNKNOWN** 唔影響 — 唔係壞，只係唔知道（e.g. Sentinel Dashboard 冇開）
- ECS 會自動重啟 DOWN 嘅 Task（self-healing）

**唔好 expose 所有 endpoint：**
- `management.endpoints.web.exposure.include=*` 會暴露 `/actuator/env`（有 secrets！）
- 只開需要嘅：`health, info, metrics`

---

## Part B: Structured Logging (MDC)

### 問題

100 個 request 同時跑，log 混埋一齊：
```
INFO ProductService : Finding product...
INFO OrderService   : Creating order...
INFO ProductService : Finding product...   ← 邊個 user？邊個 request？
```

### 解決：MDC (Mapped Diagnostic Context)

每個 request 貼一個 "名牌"：
```
INFO [reqId=fa9fb2cd] ProductService : Finding product...
INFO [reqId=5a77f7a2] OrderService   : Creating order...
INFO [reqId=fa9fb2cd] OrderService   : Stock check...
```

用 `reqId=fa9fb2cd` 就可以 filter 出同一個 request 嘅所有 log。

### LoggingFilter

```java
@Component
public class LoggingFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        try {
            String requestId = UUID.randomUUID().toString().substring(0, 8);
            MDC.put("requestId", requestId);
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();  // 一定要 clear！唔係會 leak 去下一個 request
        }
    }
}
```

**Key points：**
- `@Component` — 冇呢個 Spring 唔會 register filter
- `finally { MDC.clear() }` — 防止 thread pool reuse 時 leak 舊 requestId
- 唔使 catch Exception — `throws` 聲明畀 Spring 自己 handle

### logback-spring.xml

```xml
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss} [%level] [reqId=%X{requestId}] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    <root level="INFO">
        <appender-ref ref="CONSOLE" />
    </root>
</configuration>
```

`%X{requestId}` — 從 MDC 拎值。

### System.out.println vs Logger

| | println | Logger (SLF4J) |
|---|---|---|
| 輸出 | Console only | Console / File / CloudWatch |
| Level | 冇 | DEBUG / INFO / WARN / ERROR |
| 格式 | 冇 timestamp、class | 自動加 timestamp、thread、class |
| 性能 | synchronized（blocking） | Async appender 可用 |
| Production | 搵唔到 | 可以 filter、search |

---

## Part C: Custom Business Metrics

### 內建 Metrics

Actuator + Micrometer 自動收集：
- `http.server.requests` — request count、response time、status code
- `jvm.memory.used` — JVM memory usage
- `hikaricp.connections.active` — DB connection pool
- `jvm.gc.pause` — GC 暫停時間

### Custom Counter

```java
// OrderService constructor
private final MeterRegistry meterRegistry;

// Checkout 成功後
meterRegistry.counter("orders.checkout.count").increment();

// Cancel 成功後
meterRegistry.counter("orders.cancel.count").increment();
```

查看：`GET /actuator/metrics/orders.checkout.count`

```json
{
  "name": "orders.checkout.count",
  "measurements": [{ "statistic": "COUNT", "value": 1 }]
}
```

**注意：** Counter 要至少 increment 一次先會出現喺 metrics endpoint。

### Production 監控架構

```
App (Micrometer) → Prometheus (收集) → Grafana (Dashboard)
                                         ↓
                                    Alert → PagerDuty/Slack
```

FAANG 有自己嘅 internal 版本，但概念一樣。面試講 Prometheus + Grafana 完全 OK。

---

## 踩過嘅坑

### 1. GlobalExceptionHandler 冇 log

**Before：**
```java
@ExceptionHandler(Exception.class)
public ErrorResponse handleGenericError(Exception ex) {
    return new ErrorResponse(500, "Internal server error", null, LocalDateTime.now());
    // Exception 被食咗！Console 咩都唔見！
}
```

**After：**
```java
@ExceptionHandler(Exception.class)
public ErrorResponse handleGenericError(Exception ex) {
    log.error("Unexpected error", ex);  // 一定要 log！
    return new ErrorResponse(500, "Internal server error", null, LocalDateTime.now());
}
```

**教訓：** Catch-all handler 一定要 log exception，唔係 production debug 會好痛苦。

### 2. @ManyToOne 預設 EAGER → Cross-shard JOIN fail

ShardingSphere 唔支援 cross-shard JOIN。`findById(productId)` 會 eager load Category + User，觸發 3 table JOIN。

**Fix：**
```java
@ManyToOne(fetch = FetchType.LAZY)  // 唔會自動 JOIN
@JoinColumn(name = "category_id")
private Category category;
```

**Best practice：** 預設用 LAZY，需要 EAGER 時用 `@EntityGraph` 或 `JOIN FETCH`。

### 3. Spring Profile 分離 Sharding

日常開發唔應該受 ShardingSphere 影響：

| Profile | DataSource | 用途 |
|---|---|---|
| default | MySQL localhost:3306 | 日常開發 |
| sharding | ShardingSphere (2 shards) | Demo sharding |

`application-sharding.properties` override datasource config。

### 4. H2 scope test → runtime

ShardingSphere standalone mode 用 H2 做 metadata 存儲，但 H2 原本 scope=test，runtime 搵唔到。

### 5. RocketMQ broker IP

`brokerIP1=host.docker.internal` 喺某啲 network 環境下 unreachable。改做 `127.0.0.1`。

### 6. 加依賴要更新 Test Mock

OrderService 加咗 `MeterRegistry`，但 `OrderServiceTest` 冇 mock → NPE。`@InjectMocks` 只會注入有 `@Mock` 嘅 field。

---

## Interview Drills

<details>
<summary>Q1: Health Check 有咩用？ALB 點樣利用佢？</summary>

Health check endpoint 畀 ALB 定期 call（e.g. 每 30 秒），如果連續幾次返回非 200 或者 status=DOWN，ALB 會停止 route traffic 去呢個 Task。ECS 會自動起一個新 Task 替代。呢個實現咗 self-healing — 壞咗嘅 instance 自動被替換，唔使人手介入。
</details>

<details>
<summary>Q2: MDC 係咩？點解需要？</summary>

MDC (Mapped Diagnostic Context) 係 SLF4J 提供嘅 thread-local map，可以將 context（如 requestId, userId）注入每一行 log。喺高併發環境，多個 request 嘅 log 混埋一齊，冇 MDC 就搵唔到特定 request 嘅 log。實現方式：用 Filter 喺 request 入口 set MDC，finally 入面 clear。Logback pattern 用 `%X{key}` 讀取。
</details>

<details>
<summary>Q3: Prometheus + Grafana 嘅 pull model 同 push model 有咩分別？</summary>

Pull model：Prometheus 定期去 App 嘅 `/actuator/prometheus` endpoint 拎 metrics。好處係 App 唔使知道 Prometheus 嘅地址，簡單可靠。Push model：App 主動 push metrics 去 collector（如 StatsD）。Pull model 係 Prometheus 嘅標準做法，適合 Kubernetes/ECS 環境，因為 service discovery 可以自動搵到所有 instance。
</details>

<details>
<summary>Q4: @ManyToOne 預設係 EAGER 定 LAZY？Production 應該用邊個？</summary>

`@ManyToOne` 同 `@OneToOne` 預設 EAGER，`@OneToMany` 同 `@ManyToMany` 預設 LAZY。Production 應該全部改 LAZY，需要 eager 時用 `@EntityGraph` 或 JPQL `JOIN FETCH` 明確指定。原因：EAGER 會觸發不必要嘅 JOIN 或 N+1 query，喺 sharding 環境更會導致 cross-shard join failure。
</details>

<details>
<summary>Q5: 點解 catch-all exception handler 一定要 log？</summary>

如果 catch-all handler 只返回 500 response 但唔 log，所有 unexpected error 嘅 stack trace 會消失。Production 出問題時，你只知道 "有 500"，但唔知道 root cause。加 `log.error("Unexpected error", ex)` 確保 stack trace 寫入 log（CloudWatch / ELK），方便 postmortem 分析。呢個係 observability 嘅基本要求。
</details>
