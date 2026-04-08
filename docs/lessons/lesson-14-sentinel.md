# Lesson 14: Sentinel Traffic Control

## 🎯 目標

為系統加上**流量保護**，防止 cascading failure。學識 Sentinel 既三大武器：Flow Control、Circuit Breaking、System Protection。

---

## 一、點解需要 Sentinel？

### 核心洞見：Slow is the new down

> Netflix 2012 年因為一個 downstream service 慢咗 2 秒，拖死咗成個 Netflix website。之後整咗 Hystrix（第一代 Circuit Breaker library）。

### Cascading Failure 場景

```
Time 0s:  MySQL 正常，query 10ms，HikariCP pool=10 好輕鬆
Time 1s:  MySQL 變慢，query 3 秒
Time 2s:  10 個 connection 全部 hang 住等 DB
Time 5s:  Tomcat 200 個 thread 全部 hang 住
Time 10s: 連 /health 都無反應 → 整個 app 死
```

### 「慢」比「錯」可怕

- **錯** = 立即 return，thread 即刻釋放，pool 有位
- **慢** = Thread 被佔住 N 秒，pool 會爆

**數學**：
- 正常 RT 50ms → Tomcat 200 threads 每秒處理 4000 req
- 慢到 5s → 每秒處理 40 req
- **容量跌 100 倍**，但流量無變 → 雪崩

### Nginx rate limiting 救唔到

問題唔係「太多 request」，係「每個太慢」。Nginx `limit_req` 只睇 QPS，睇唔到 RT。

---

## 二、Sentinel 三大武器

| 武器 | 作用 | 觸發條件 |
|---|---|---|
| **Flow Control** 流控 | 限 QPS / 並發線程數 | 量超標 |
| **Circuit Breaking** 熔斷 | 快速失敗，保護 downstream | downstream 慢 / 錯 |
| **System Protection** 系統保護 | 全局最後防線 | CPU / Load / RT / QPS |

---

## 三、架構：Dashboard + App

```
App (8719) ←──[rules push]── Dashboard (8858)
App  ──[metrics push]──→ Dashboard (8858)
```

- **8858**：Dashboard web UI，app 推 metrics 上去
- **8719**：app 開既 port，等 Dashboard push rules 落嚟

**點解兩個 port**：
1. 解耦 — Dashboard 死咗，app 照樣跑（rules cached）
2. 一對多 scaling — Dashboard 要主動 push rule 去 100 個 instance

### 配置

**pom.xml**:
```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>
    <version>2025.0.0.0</version>
</dependency>
```

**application.properties**:
```properties
spring.cloud.sentinel.transport.dashboard=localhost:8858
spring.cloud.sentinel.transport.port=8719
spring.cloud.sentinel.eager=true
```

⚠️ **版本選擇**：Spring Cloud Alibaba 版本號對應 Spring Cloud release train。**2025.0.0.0** 係目前唯一官方支援 Boot 3.5.x 既版本。如果用舊版（例如 2023.0.1.2，只支援 Boot 3.2/3.3），要加 `spring.cloud.compatibility-verifier.enabled=false` 先可以跑起。

**啟動 Dashboard**（獨立 jar）：
```bash
java -Dserver.port=8858 -Dproject.name=sentinel-dashboard -jar sentinel-dashboard-1.8.8.jar
```

預設登入：`sentinel / sentinel`

---

## 四、Flow Control 流控

### 設 rule

Dashboard → 流控规则 → 新增：
- 资源名：`/api/products`
- 阈值类型：QPS
- 单机阈值：2

### Fixed vs Sliding Window

**Fixed Window 問題**：
```
Time: 0.9s  1.1s
      req1  req2  ← 0-1 秒窗口有 1 個，1-2 秒窗口有 1 個
               但 0.9-1.1 呢 0.2 秒實際有 2 個 request
```
最壞情況實際 QPS 係設定值既 **2 倍**（交界爆發）。

**Sliding Window**：每時刻睇「過去 1 秒」，唔存在邊界問題。Sentinel 預設用呢個。

### 觸發測試

```bash
for i in {1..10}; do curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/products & done; wait
```

預期：2 個 `200` + 8 個 `429`。

**429 Too Many Requests** — HTTP 標準既 rate limit status (RFC 6585)。

---

## 五、Circuit Breaking 熔斷

### 狀態機

```
CLOSED (正常)
   ↓  慢調用比例 > 50%
OPEN (熔斷，fast fail)
   ↓  10 秒過後
HALF_OPEN (試探)
   ↓ 放 1 個 request
   ├── 成功 → CLOSED
   └── 失敗 → OPEN（再熔斷 10 秒）
```

**關鍵**：HALF_OPEN 只放 **1 個** test request，唔會一次放大量 request 重新壓死 downstream。

### 三種熔斷策略

| 策略 | 依據 | 場景 |
|---|---|---|
| 慢調用比例 | RT > threshold 比例 | downstream 變慢 |
| 異常比例 | 異常率 | downstream 拋 exception |
| 異常數 | 異常次數 | 低流量場景 |

### 設 rule 示範

- 資源名：`slowEndpoint`
- 策略：慢調用比例
- 最大 RT：500ms
- 比例閾值：0.5
- 熔斷時長：10s
- 最小請求數：5（**避免低流量誤判** — 樣本太少結論唔可信）
- 統計時長：1000ms

### Test endpoint

```java
@GetMapping("/slow")
@SentinelResource(
    value = "slowEndpoint",
    blockHandler = "slowBlockHandler",
    fallback = "slowFallback"
)
public String slowEndpoint() throws InterruptedException {
    Thread.sleep(2000);
    return "slow response";
}

public String slowBlockHandler(BlockException ex) {
    return "【降級】服務繁忙中，請稍後再試 (blocked by: " + ex.getClass().getSimpleName() + ")";
}

public String slowFallback(Throwable ex) {
    return "【降級】服務暫時不可用 (error: " + ex.getMessage() + ")";
}
```

### 觸發熔斷

⚠️ **坑**：10 個並發 request 同時到達時，Sentinel **未有任何完成數據**，全部會放行！因為 Sentinel 係 request **完成後**先更新統計。

解決：**跑兩輪**
```bash
# Round 1: 預熱，令熔斷 trip 開
for i in {1..10}; do curl -s -o /dev/null http://localhost:8080/api/products/slow & done; wait

# Round 2: 驗證被降級（必須喺 10 秒窗口內）
for i in {1..10}; do curl -s http://localhost:8080/api/products/slow; echo; done
```

### Fast Fail 既威力

```
req1: 200 time=2.01s    ← 真正打到 endpoint
req2: 429 time=0.019s   ← fast fail
...
req10: 429 time=0.004s  ← fast fail
```

**Thread 佔用時間由 20 秒 → 2 秒**，容量提升 10 倍。

---

## 六、`@SentinelResource`：`blockHandler` vs `fallback`

### 呢個係面試大熱

| | `blockHandler` | `fallback` |
|---|---|---|
| **觸發** | `BlockException`（被 Sentinel rule 攔截） | 業務拋**非** `BlockException` |
| **含義** | 「系統保護機制攔截」 | 「業務真係出錯」 |
| **參數** | 原方法參數 + `BlockException` | 原方法參數 + `Throwable`（optional） |
| **回應** | 友好 message，畀 user retry 希望 | 兜底 / log / 通知 oncall |

**優先順序**：同時拋兩種 exception 時，`BlockException` 先走 `blockHandler`，唔會去 `fallback`。

### 硬性規定

1. handler method **return type 必須同原方法一致**
2. handler 必須**同 class**（或用 `blockHandlerClass` 指定外部 class）
3. `blockHandler` 參數 = 原方法參數 + `BlockException`（放最後）
4. `fallback` 參數 = 原方法參數 + `Throwable`（optional，放最後）

### Status code 變化

| | URL-based | `@SentinelResource` + blockHandler |
|---|---|---|
| Status | `429` | `200` |
| Body | Sentinel default | **你**決定 |

因為 `blockHandler` 成功 return String，Spring MVC 視為正常返回。

---

## 七、`@SentinelResource` + `@Transactional` 陷阱

### 熔斷觸發時 DB 點？

**答：完全冇 touch DB**。

```
Request → Sentinel rule 攔截 → 拋 BlockException
                 ↓
            blockHandler 返 message
                 ↓
       原 method 根本冇執行 → @Transactional 無 begin
```

Sentinel 既保護層級 > Spring transaction 層級。

### `fallback` 觸發時又點？

```
Method 執行到一半 → 拋 SQLException
       ↓
  @Transactional 捕獲 → rollback ✅
       ↓
  Sentinel 接住 → call fallback → return friendly message
```

**Transaction 已經 rollback 咗**，fallback 只係控制 response 格式。

---

## 八、System Protection 系統保護

### 全局保護（唔指定 resource）

| 指標 | 含義 |
|---|---|
| Load | 系統 1 分鐘負載（Linux/macOS） |
| RT | 所有 resource 平均 RT |
| 並發線程數 | 全局 in-flight |
| 入口 QPS | 全局 total QPS |
| CPU | 0.0 - 1.0 |

### BBR 算法 💡

Sentinel system protection 用類似 TCP 擁塞控制既思想：

> **吞吐量 = 並發數 × (1 / RT)**

當並發增加但吞吐量唔升反跌時，系統已 saturated → throttle。呢個係 **Little's Law** 既應用。面試識講就 bonus 分。

### 測試

Rule：入口 QPS = 2，然後：
```bash
for i in {1..30}; do (curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/products > /tmp/r$i.txt) & done
wait
for i in {1..30}; do echo "req$i: $(cat /tmp/r$i.txt)"; done
```

預期：2 個 `200` + 28 個 `429`。

---

## 九、三大武器對比

| 機制 | 粒度 | 適用 |
|---|---|---|
| Flow Control | 單個 resource | 預知某 endpoint 熱門 |
| Circuit Breaking | 單個 resource | 預知某 downstream 會慢 |
| System Protection | 全局 | 防範未知、最後防線 |

**Production 三者並用**：
- Nginx 層 DDoS 防護
- Sentinel Flow Control 保護熱 endpoint
- Sentinel Circuit Breaking 保護外部依賴
- Sentinel System Protection 做全局兜底

---

## 十、Sentinel vs Nginx

| | Nginx `limit_req` | Sentinel |
|---|---|---|
| 層級 | L7 代理層，**外部** | JVM 內，**應用層** |
| 可見度 | URL / IP / header | method 參數、業務狀態 |
| 熔斷 | ❌ 無 | ✅ 支持 |
| 動態 rules | reload config | Dashboard 即時 push |
| 降級 | 返 503 | Custom `blockHandler`/`fallback`，可返 cached data |

**一句話**：Nginx = 大門保安；Sentinel = 應用內醫生。**互補**，production 都要用。

---

## 十一、痛點：Rules 持久化

### 問題

Sentinel 預設 rules 存喺 **memory**，app restart 就清零。Dev 用 Dashboard click 既 rule 全部消失 😭

### Production 方案

| 方案 | 特點 | 規模 |
|---|---|---|
| File (local) | 簡單 | dev |
| **Nacos** | 阿里標配，配置中心 + push | 中大型 |
| Apollo | 攜程出品 | 中大型 |
| **Azure App Configuration** | 雲原生配置中心 | Azure 生態 |
| **AWS AppConfig / Parameter Store** | 雲原生配置中心 | AWS 生態 |
| **GCP Runtime Config** | 雲原生配置中心 | GCP 生態 |
| ZooKeeper | legacy | - |
| Redis | 簡單通用 | 中小型 |

**Push vs Pull**：
- Pull: Sentinel 定時讀 storage，有延遲
- Push: storage 變更主動通知，實時

Nacos 用 **long polling**（折衷），實時 + 簡單。

Sentinel 官方提供 `sentinel-datasource-*` adapter 系列，社群有 Azure/AWS 既實現。自己寫都唔難 — 實現 `ReadableDataSource<String, List<Rule>>` interface 就得。

### ⚠️ Config Service vs Secret Service — 完全唔同嘢

| | **Config Service** | **Secret Service** |
|---|---|---|
| **例子** | Azure App Configuration、AWS AppConfig、GCP Runtime Config、Nacos、Apollo | Azure Key Vault、AWS Secrets Manager、GCP Secret Manager |
| **儲咩** | **可變配置**（feature flags、rules、endpoints） | **機密**（密碼、API key、certificate） |
| **特點** | Dynamic refresh、push notification、versioning、rollback | 加密儲存、嚴格 access control、通常 read once at startup |
| **用途** | Sentinel rules、business config | DB password、JWT secret、external API credentials |

**Sentinel rules 應該用 Config Service，唔係 Secret Service**。兩者定位完全唔同，唔好混淆。

---

## 十二、關鍵概念總結 🎓

1. **Slow is the new down** — 慢 downstream 會拖死整個 app
2. **Fast Fail** — 快速失敗保護 thread pool
3. **Circuit Breaker 狀態機** — CLOSED → OPEN → HALF_OPEN → CLOSED
4. **Sliding Window** > Fixed Window（避免邊界爆發）
5. **最小請求數** — 避免低流量樣本誤判
6. **`blockHandler` vs `fallback`** — 系統保護 vs 業務異常
7. **Sentinel 保護層級 > Spring Transaction** — 熔斷時 DB 根本無被 touch
8. **System Protection + BBR** — 全局自適應保護
9. **Rules 持久化** — Production 用 Nacos / Apollo

---

## 十三、面試題回顧

**Q: 點解「慢」比「錯」可怕？**
- 用戶：錯即刻知，慢就 hang 住
- 系統：錯就釋放 thread，慢會佔死 pool → cascading failure
- 傳染：慢會向上游傳播

**Q: Fixed Window 有咩問題？**
- 邊界爆發，最壞情況實際 QPS 係設定值 2 倍

**Q: Circuit Breaker HALF_OPEN 做咩？**
- 只放 1 個 request 試探 downstream 有冇恢復，避免一次放全部重新壓死

**Q: `blockHandler` 同 `fallback` 分別？**
- blockHandler：被 Sentinel 攔截（系統保護）
- fallback：業務真係拋 exception
- 設計上分開，方便 debug 同 monitoring

**Q: `@SentinelResource` + `@Transactional` 熔斷時 DB 會 rollback 嗎？**
- 唔會。熔斷時原 method 根本冇執行，transaction 都無 begin
- Sentinel 保護層級 > Transaction 層級

**Q: Sentinel vs Nginx rate limiting？**
- Nginx 外部大門，Sentinel 內部醫生
- Sentinel 有熔斷，Nginx 冇
- Sentinel 知道業務語義，Nginx 只知 URL/IP
- Production 兩者並用

**Q: Production 點解決 rules restart 就消失？**
- 用配置中心 Nacos / Apollo
- App 啟動讀初始 rules，運行時 subscribe 變更
- 唔好用 secret manager（定位唔同）
