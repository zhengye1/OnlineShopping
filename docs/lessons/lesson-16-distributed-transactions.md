# Lesson 16: Distributed Transactions 分佈式事務

**日期**: 2026-04-09
**老師**: Lyon
**學生**: 小V

---

## 📖 今日學咩

單機 ACID 喺分佈式世界失效，點樣「接近」佢？由理論到方案，完整走一次分佈式事務。

- CAP 定理 + BASE 理論
- 2PC 點解喺互聯網已死
- Saga Pattern（Choreography vs Orchestration）
- TCC 模式（Try-Confirm-Cancel）
- 本地消息表 / RocketMQ 事務消息（連接 Lesson 11-12）
- 實戰：Saga Log 機制加入 checkout flow

---

## 1. 核心問題：@Transactional 嘅邊界

```java
@Transactional
public void checkout() {
    deductStock();   // shard0
    createOrder();   // shard1
}
```

`@Transactional` 只保證**同一個 DB Connection** 嘅原子性。跨 DB / 跨服務 = 失效。

**結果**: shard0 扣咗 stock，shard1 order 失敗 → 庫存扣咗但冇訂單 → 數據永遠對唔返。

---

## 2. CAP 定理 — 分佈式世界嘅基本定律

**三個字母**:
- **C** (Consistency): 所有 node 讀到同一份最新數據
- **A** (Availability): 每個請求都得到 response
- **P** (Partition Tolerance): 網絡斷開情況下系統仲要 work

**核心結論**: 三者只能同時滿足兩個。而 P 係必然發生嘅，所以實際係 **CP vs AP 二選一**。

### 判斷框架

> **如果數據短暫不一致，最壞會發生咩？**
> - 「錢 / 庫存 / 身份出錯」→ **CP**
> - 「用戶見到舊少少」→ **AP**

**記憶口訣：「錢 CP，畫面 AP」**

### 實際應用

| 模塊 | CP / AP | 原因 |
|---|---|---|
| 扣庫存、扣錢 | CP | 超賣 = 災難 |
| 商品列表、搜索 | AP | 遲少少冇所謂 |
| 點讚、收藏 | AP | 計錯 1 個冇所謂 |

**同一個系統可以混用 CP + AP** — 我哋嘅 Redis cache (AP) + MySQL 扣庫存 (CP) 就係。

---

## 3. BASE 理論 — AP 派嘅哲學宣言

| 字母 | 原詞 | 意思 |
|---|---|---|
| **BA** | Basically Available | 基本可用（降級 / 限流 / 熔斷） |
| **S** | Soft state | 軟狀態（允許中間態暫時存在） |
| **E** | Eventually consistent | 最終一致性 |

**ACID vs BASE 係光譜嘅兩端**，唔係對立嘅。

**連接過往 Lesson**: Sentinel（BA 嘅工具）、RocketMQ HALF 消息（S 嘅體現）、事務消息（E 嘅實現）。

---

## 4. 2PC (Two-Phase Commit) — 點解已死 ☠️

### 流程
- **Phase 1 Prepare**: Coordinator 問所有 DB「準備好 commit 未？」→ 全部 lock 住 row 等指令
- **Phase 2 Commit**: 全員 YES → commit；任一 NO → rollback

### 五大死因

| 死因 | 問題 |
|---|---|
| **同步阻塞** | Prepare 到 Commit 期間，所有 row 鎖住 |
| **Coordinator 單點故障** | Coordinator crash → 全部 participant 永遠鎖住 |
| **網絡不可靠** | Commit 消息丟失 → 部分 commit 部分 rollback → 不一致 |
| **性能災難** | 單機 10ms → 2PC 500ms+，性能蝕 20 倍 |
| **可擴展性為零** | 假設靜態小規模系統，唔符合互聯網 |

**諷刺點**: 2PC 犧牲咗性能 + 可用性，但喺網絡分區下**仲要達唔到一致性**。

**記憶口訣：「2PC 鎖住等人，Saga 做住先錯咗再救」**

---

## 5. Saga Pattern ⭐ 主角

### 核心思想

> 將一個長事務拆成**一系列本地事務**，每個配一個**補償動作**。失敗就倒序執行補償。

```
成功: T1 → T2 → T3 → T4 ✅
失敗: T1 → T2 → T3 ❌ → C2 → C1 (倒序補償)
```

### 關鍵概念

- **補償 ≠ rollback** — 係語義反向操作（扣 stock → 加返 stock）
- **補償必須冪等** — 因為網絡 retry 可能執行多次
- **Saga 冇隔離性** — 其他事務可以睇到中間態

### 兩種實現模式

| | Choreography 編舞 | Orchestration 編排 |
|---|---|---|
| **協調方式** | 事件驅動，去中心 | 中央 Orchestrator |
| **適合** | 簡單流程、fire-and-forget | 複雜流程、需要補償 |
| **可見性** | 差（流程散落各服務） | 好（一個地方管所有） |

### 判斷 Choreography vs Orchestration

```
有條件分支？ → Orchestration
步驟間有嚴格依賴？ → Orchestration
需要補償？ → Orchestration
Fire-and-forget 通知型？ → Choreography
```

**例子**:
- 用戶註冊 → 發 email + 送優惠券 → **Choreography**（fire-and-forget）
- Checkout → 扣 stock + 建 order + 扣錢 → **Orchestration**（需要補償）

### Checkout Saga 設計

| Step | Action | Compensation | 點解要 / 唔要補償 |
|---|---|---|---|
| 1 | `deductStock()` | `restoreStock()` | 唔補償 = 商品永遠鎖住 |
| 2 | `createOrder()` | `markAsFailed()` | 保留 order 讓用戶重試付款 |
| 3 | `chargePayment()` | `refundPayment()` | 錢嘅嘢零容忍 |
| 4 | `clearCart()` | 不補償 | 用戶想重試付款，唔係重新揀貨 |

### Saga 4 大坑

1. **補償操作要冪等** — 用 compensation_id 去重
2. **補償無法補償** — 把難以補償嘅操作放到最後（先扣錢再發貨）
3. **隔離性缺失** — 用 semantic lock / commutative updates 補救
4. **Orchestrator crash** — Saga 狀態必須持久化到 DB

### 補償設計 3 個層次

1. **技術補償**: 純反向操作（扣 stock → 加返 stock）
2. **業務補償**: 業務規則決定（已發貨 → 發客服處理）
3. **意圖補償**: 保留用戶意圖（付款失敗 → 保留 order 讓用戶重試）⭐

---

## 6. TCC 模式 (Try-Confirm-Cancel)

### 核心思想

> 將每個操作拆成「預留資源 → 確認使用 → 取消預留」三階段。用**業務層 soft lock**（frozen 欄位）模擬強一致。

### 例子：轉帳

- **Try**: `balance -= 100, frozen += 100`（凍結，未真正離開）
- **Confirm**: `frozen -= 100`（真正扣走）
- **Cancel**: `balance += 100, frozen -= 100`（還返）

### TCC vs Saga

| | Saga | TCC |
|---|---|---|
| 資源預留 | ❌ 冇，直接執行 | ✅ Try 階段凍結 |
| 隔離性 | ❌ 差 | ✅ 好 |
| 業務侵入 | 中 | 💀 高（3 個 method + 改 schema） |
| 代碼量 | x | 2-3x |

### TCC 3 大坑

| 問題 | 成因 | 解決 |
|---|---|---|
| **空回滾** | Cancel 比 Try 先到達 | 檢查 Try log 存在性 |
| **懸掛** | Try 遲到，Cancel 已執行 | 檢查空回滾標記 |
| **冪等** | 網絡重試 | 所有階段用 txId 去重 |

### 點揀？

- **有 reservation 語義**（門票、座位、限時鎖定）→ **TCC**
- **其他場景** → **Saga**（90% 情況）

**記憶口訣：「有 reservation → TCC，冇就 Saga」**

---

## 7. 本地消息表 / RocketMQ 事務消息

### 本質

> 業務操作 + 消息記錄用**同一個 DB transaction** 保證原子性，之後異步發送到 MQ。

### 你 Lesson 12 寫嘅 RocketMQ 事務消息 = 本地消息表工業版

| | 手動本地消息表 | RocketMQ 事務消息 |
|---|---|---|
| 原子性保證 | 同一 DB transaction | HALF 消息 + 回查 |
| 消息投遞 | Background job 掃表 | RocketMQ 自動 |
| 額外 DB 表 | 要 | 唔要 ✅ |
| Background job | 要自己寫 | 唔要 ✅ |

### 限制

只保證「消息一定會送到」，唔保證「下游一定成功」。
兜底：**冪等 + 重試 + 對賬**（最終一致性三板斧 🪓🪓🪓）。

---

## 8. 四大方案 Decision Tree

```
強一致 ←────────────────────→ 最終一致
  2PC  →  TCC  →  Saga  →  本地消息表
  (已死)  (金融)   (主流)    (最常用)
```

| 場景 | 推薦方案 |
|---|---|
| Checkout 多步驟 + 補償 | Saga |
| 扣錢包 / 支付核心 | TCC |
| 下單後發通知 email | MQ 事務消息 |
| 下單後 sync ES 索引 | MQ 事務消息 |
| 演唱會門票搶購 | TCC |
| 用戶註冊 → 送優惠券 | Choreography / MQ |

---

## 9. 🛠️ 實戰：Saga Log 機制

### 設計思路

> Monolith 入面 **唔需要** 改成真 Saga（`@Transactional` 嘅 ACID 嚴格好過最終一致性）。
> 但加入 **Saga 狀態記錄**，為將來拆微服務做準備。

**YAGNI 原則**: Saga 係 @Transactional 嘅**降級方案**，能用本地事務就唔好用 Saga。

### 新增代碼

- `SagaStatus` enum: STARTED / COMPLETED / COMPENSATING / COMPENSATED / FAILED
- `SagaExecution` entity: 記錄 Saga 整體狀態 (type, userId, orderId)
- `SagaStepLog` entity: 記錄每一步 (stepOrder, stepName, status, errorMessage)
- `SagaExecutionRepository`: JPA repository
- `OrderService.doCheckout()`: 每步加 SagaStepLog 記錄

### 漸進式改造路徑

```
而家 (monolith):     @Transactional 保證 ACID + SagaLog 觀察記錄
將來 (微服務):        移除 @Transactional + SagaLog 變 Orchestrator state store + 加補償邏輯
```

---

## 10. 🎤 Interview Drills（5 條面試題 + Model Answer）

### Q1: 解釋 CAP 定理。你個系統係 CP 定 AP？

**記憶口訣：「錢 CP，畫面 AP」**

<details>
<summary>Model Answer（點擊展開）</summary>

> "CAP says a distributed system can only guarantee two of Consistency, Availability, and Partition Tolerance. Since network partitions are inevitable, the real choice is **CP vs AP**.
>
> Our system uses **both**: checkout (stock/payment) is **CP** — we'd rather return an error than oversell. Product browsing and search is **AP** — a slightly stale cache is fine for better user experience."

</details>

---

### Q2: 2PC 同 Saga 嘅最大分別係咩？點解互聯網公司唔用 2PC？

**記憶口訣：「2PC 鎖住等人，Saga 做住先錯咗再救」**

<details>
<summary>Model Answer（點擊展開）</summary>

> "The biggest difference: **2PC holds database locks** across all participants until everyone commits — synchronous blocking. **Saga commits each step immediately** and uses compensating actions on failure — no distributed locks.
>
> Internet companies avoid 2PC because:
> 1. **Synchronous blocking** kills throughput under high concurrency
> 2. **Coordinator SPOF (Single Point of Failure)** — if it crashes, all participants freeze with locks held
> 3. Despite paying this huge cost, it still **can't guarantee consistency** under network partitions"

</details>

---

### Q3: 你個 checkout flow 跨了兩個 DB shard，@Transactional 失效。你會點處理？

**記憶口訣：「每步配反向操作，失敗就倒帶」**

<details>
<summary>Model Answer（點擊展開）</summary>

> "I'd use the **Saga pattern with Orchestration**. Break checkout into local transactions:
>
> 1. `deductStock()` → compensate: `restoreStock()`
> 2. `createOrder()` → compensate: `cancelOrder()`
> 3. `chargePayment()` → compensate: `refundPayment()`
> 4. `clearCart()` → no compensation needed (user wants to retry payment, not re-select items)
>
> Each step commits independently. If step 3 fails, the orchestrator runs compensations in reverse order. All compensations must be **idempotent** to handle retries safely."

</details>

---

### Q4: Saga 嘅補償操作一定要滿足咩條件？點解？

**記憶口訣：「冪等 = 做一次同做十次結果一樣」**

<details>
<summary>Model Answer（點擊展開）</summary>

> "Saga compensations must be **idempotent** — executing the same compensation multiple times produces the same result. This is because network failures may cause retries, and without idempotency you get data corruption (e.g., restoring stock twice = stock inflated).
>
> Implementation: use a unique `compensation_id`. Before executing, check if it's already been processed. If yes, return success without re-executing."

</details>

---

### Q5: 用戶付款成功，但發貨系統 timeout 咗，你唔知佢到底收到冇。你會點做？

**記憶口訣：「Timeout ≠ 失敗，retry + 冪等 + 對賬」**

<details>
<summary>Model Answer（點擊展開）</summary>

> "I **cannot** assume failure just because of a timeout — the shipping service may have actually processed it. So:
>
> 1. **Don't compensate immediately** — timeout ≠ failure
> 2. **Retry with idempotent request** — send the same shipping request with the same `request_id`. If shipping service already processed it, it returns success without re-shipping
> 3. **If retries exhausted** → put into a **dead letter queue** for manual handling or automated reconciliation
> 4. **Reconciliation job** as safety net — periodically scan orders in `PAID` status that have no corresponding shipping record after X minutes, and trigger re-shipping
>
> The key insight: in distributed systems, **timeout is the hardest case** — it's neither success nor failure. The safe response is **retry with idempotency**, never assume."

</details>

---

### Quick Reference

| 題目 | 一句話記憶 |
|---|---|
| CAP 定理 | **錢 CP，畫面 AP** |
| 2PC vs Saga | **2PC 鎖住等人，Saga 做住先錯咗再救** |
| 跨 shard checkout | **每步配反向操作，失敗就倒帶** |
| 補償條件 | **冪等 = 做一次同做十次結果一樣** |
| Timeout 處理 | **Timeout ≠ 失敗，retry + 冪等 + 對賬** |
| TCC 適用場景 | **有 reservation 語義 → TCC，冇就 Saga** |

---

## 11. 🧠 核心 Takeaway

> **「單機 ACID 喺分佈式世界已經死咗，我哋用 BASE 思想接近佢。」**
> **「Saga = 每步配補償，失敗就倒帶。」**
> **「最終一致性三板斧：冪等、重試、對賬。」**

---

## 下一課預告

**Lesson 17: CI/CD Pipeline** — 進入 Phase 4 生產部署階段。GitHub Actions 自動化測試 + 部署。
