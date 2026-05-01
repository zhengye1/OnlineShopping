# Lesson 32: Cart UX Overhaul — Stepper, Debounce, Optimistic Reducer

**Date:** 2026-04-29 ~ 2026-04-30

L31 完成 checkout flow，cart `<input> + [Update]` 嘅形式 work 但 UX 落後業界標準 10 年。L32 將 cart quantity UX 升級成 hybrid stepper（`[- input +]`），順手解決 race condition + 講 client-side state architecture。

完整功能：per-row `<CartRow>` component、stepper buttons + controlled input、500ms debounce useEffect、multi-action `useOptimistic` reducer、`startTransition` 包裹 optimistic dispatch。

⚠️ **L31 留底 trap 重溫** —— `useOptimistic` setter 必須喺 transition 入面 dispatch（form action 自動 wraps；onClick handler 要 `startTransition` 手動 wrap）。L32 第一個 trap 就係呢個。

---

## 核心概念

### 1. Per-row client component —— State isolation 嘅必要

L31 全張 list 一個 client component。L32 cart 每行有自己嘅：
- `pendingQty` (用戶意圖嘅 quantity，可以快過 server)
- Debounce timer
- Optional：將來嘅 `isPending`、`error` per row

如果 hoist 上去 `CartItemList`，要 manage：
```ts
const [qtyMap, setQtyMap] = useState<Map<number, number>>();
const [pendingMap, setPendingMap] = useState<Set<number>>();
```

**3 個 cost：**
1. **Complexity 暴增** —— Map / Set 操作 boilerplate、新 row 加 entry、刪 row 清 entry
2. **Re-render scope** —— 任何一行 state change 觸發**整個 list re-render**，per-row state 只 re-render 該行
3. **Encapsulation 散開** —— 「commit qty」嘅完整流程散落 list + row 兩處

呢個係 React 嘅基本 idiom：**state owns the smallest scope that uses it**。

---

### 2. Controlled input —— Stepper 嘅必要選擇

```tsx
// ❌ Uncontrolled — DOM 自己 hold value，React 唔知
<input defaultValue={qty} />
<button onClick={() => /* 點將 input UI 變 next? */ }>+</button>

// ✅ Controlled — React state 同 DOM value 永遠同步
<input value={qty} onChange={(e) => setQty(Number(e.target.value))} />
<button onClick={() => setQty((prev) => prev + 1)}>+</button>
```

**原因**：點 `+` button 想 program 改 input value `2 → 3`。Uncontrolled 嘅情況 `defaultValue` 只係 first render initial，之後 React 唔再 sync DOM。Stepper buttons 改 React state，自動 re-render 反映落 input value。

**Source of truth 對比表：**

| | Source of truth | React 知唔知 | 改 input |
|---|---|---|---|
| Uncontrolled (`defaultValue`) | DOM | ❌ | 要 ref 抓 DOM |
| Controlled (`value` + `onChange`) | React state | ✅ | `setState` 即更新 |

任何時候有 button / 程式邏輯要改 input value，必須 controlled。

---

### 3. `startTransition` —— Optimistic dispatch outside form action

L31 已踩過呢個 trap。L32 stepper button click handler 要刪 row（qty=0 path）嗰陣再撞：

```
[browser] An optimistic state update occurred outside a transition or action.
To fix, move the update to an action, or wrap with startTransition.
```

修法：
```ts
import { startTransition } from "react";

onClick={() => startTransition(() => commitQty(qty - 1))}
```

**Rule of thumb**：
- ✅ `<form action={async () => { dispatch(...) }}>` —— React auto-wrap transition
- ❌ `onClick={() => dispatch(...)}` —— 純 event handler，要手動 `startTransition`

精準診斷 + 最小 wrap：只需 wrap **真正會 dispatch 嘅 path**。`+` button 永遠唔到 `qty === 0`，唔需 wrap。`-` button + qty=1 先進 remove path，需要 wrap。

---

### 4. `useEffect` cleanup —— Debounce 嘅核心 mechanism

```tsx
useEffect(() => {
  if (pendingQty === item.quantity) return;        // initial mount + sync state guard
  const timer = setTimeout(() => {
    commitQty(pendingQty);
  }, 500);
  return () => clearTimeout(timer);                 // ← cleanup 喺 next effect 之前 run
}, [pendingQty]);
```

**Cleanup function 喺 React effect lifecycle 兩個時間點 run：**
1. 下次 effect 之前（dep 變咗）
2. Component unmount 時

**Mechanic：**
- T=0: pendingQty=8 → effect run → schedule T1 (timer)
- T=200: pendingQty=9 → cleanup run (clear T1) → effect run → schedule T2
- T=400: pendingQty=10 → cleanup run (clear T2) → effect run → schedule T3
- T=900: T3 fire → fetch quantity=10

**5 次 click 連環 → 1 次 fetch**。每次新 click reset timer。

⚠️ **Initial mount guard**：第一次 render `pendingQty === item.quantity` 必然成立，但係 useEffect 第一次都會 run。冇 guard 就會 fire 一次 useless `quantity = item.quantity` 嘅 PUT request。

---

### 5. Functional updater —— Stale closure 嘅根本解

```tsx
// ❌ Closure 抓 render-time qty，連續快撳會 stale
onClick={() => setPendingQty(qty + 1)}

// ✅ Functional updater 永遠攞最新 prev
onClick={() => setPendingQty((prev) => prev + 1)}
```

**連續 5 click 喺同一 render frame 入面：**

| | Closure | Functional |
|---|---|---|
| Click 1 | qty=2 → setPendingQty(3) | prev=2 → 3 |
| Click 2 | qty=2 (stale) → setPendingQty(3) | prev=3 → 4 |
| Click 3 | qty=2 (stale) → setPendingQty(3) | prev=4 → 5 |
| Click 4 | qty=2 (stale) → setPendingQty(3) | prev=5 → 6 |
| Click 5 | qty=2 (stale) → setPendingQty(3) | prev=6 → 7 |
| Final | 3 ❌ | 7 ✅ |

React state setter 嘅 functional form 係**專門解決呢個 problem** —— 攞 prev state 由 React 提供，永遠最新。

**Rule**：onClick handler 入面要根據 current state 計新 state，**永遠用 functional updater**，唔好用 closure。

---

### 6. `useOptimistic` reducer with multi-action union

L29 / L30 嘅 `useOptimistic` 只 cover 一種 action（remove）。L32 升級成 reducer pattern：

```ts
// lib/cart/types.ts
export type CartOptimisticAction =
  | { type: "remove"; productId: number }
  | { type: "update"; productId: number; quantity: number };
```

```tsx
// CartItemList.tsx
const [optimisticItems, dispatch] = useOptimistic<CartItem[], CartOptimisticAction>(
  items,
  (current, action) => {
    switch (action.type) {
      case "remove":
        return current.filter((i) => i.productId !== action.productId);
      case "update":
        return current.map((i) =>
          i.productId === action.productId
            ? { ...i, quantity: action.quantity }
            : i,
        );
    }
  },
);
```

**Pattern 同 `useReducer` 一樣**：
- State：`CartItem[]`
- Action：tagged union
- Reducer：純函數 `(state, action) => newState`
- Dispatch：`(action) => void`

對比：

| | useReducer | useOptimistic |
|---|---|---|
| State source | 純 client | Server state + optimistic overlay |
| Re-sync | 手動 | 自動（server response 後 revalidate） |
| 用途 | Complex client state | Server mutation 嘅 instant feedback |

Reducer 嘅 exhaustiveness 由 TypeScript narrow `action.type` 保證 —— 加新 action type 漏 case → compile error。

---

### 7. Parent-level optimistic vs row-local state —— 兩條獨立路

呢度有個 subtle architectural lesson：

```
optimisticItems (parent state)              ← Reducer dispatch update
    ↓ map → 將 item 傳俾 CartRow
CartRow 收到 item
    ↓ useState(item.quantity)               ← 只用作 initial value
pendingQty (local state)                    ← 用戶撳按鈕直接改
    ↓ render
UI 顯示 pendingQty                          ← 視覺源頭
```

**關鍵**：`useState(item.quantity)` 嘅 initial value **只用一次**（first render）。之後 `item.quantity` prop 變，pendingQty **唔會自動 sync**。

**意思係：**
- Stepper button 即時改 pendingQty → UI 即刻反映 ✅
- `optimisticItems` reducer dispatch update → **CartRow 視覺上 0 改變**（因為 row 用 local state）
- 即使刪走 reducer 嘅 update case，row stepper 都仲係正常 work

**咁仲為咩做 reducer dispatch？**
- 為將來 cart total / header count 從 `optimisticItems` aggregate 鋪墊
- Pattern consistency —— remove + update 都經 reducer 統一 mental model
- Preventing dead code（reducer 寫咗 update case 就要 dispatch，否則 dead branch）

呢個係 **「same logical state，兩個物理 representation」** 嘅例子。Production 越大 app 呢類 dual representation 越多，要刻意管理 sync。

---

### 8. Race condition —— L32 揀「接受」嘅 trade-off

連續 4 次 debounced fetch：
```
T=0    PUT quantity=8 (response 來緊)
T=600  PUT quantity=9
T=1200 PUT quantity=10
T=1800 PUT quantity=11
```

Network jitter 之下 response 可以 **out of order**：
```
T=2000 Response (quantity=11) arrives → revalidate → 顯示 11 ✅
T=2100 Response (quantity=8) arrives  → revalidate → 顯示 8  ❌
```

**L32 揀「D」方案：接受 race，靠 revalidatePath 最終一致**。

3 個其他選擇：

| Solution | 點 work | 缺點 |
|---|---|---|
| **A. AbortController** | 每次新 fetch 前 `controller.abort()` 上一個 | Server 仍處理；20 行 code |
| **B. Sequential queue** | 第二個 mutation 等第一個完先 fire | 5 click = 5 × server time，UX 差 |
| **C. Request ID counter** | 每 fetch 帶 ID，response 到時 check 仲係咪最新 | 純 frontend 但 fire-and-forget 唔需要 |
| **D. 接受 race** ✅ | 0 code | 1% case 短暫不一致 |

**揀 D 嘅理由：**
1. Backend ~50ms response，500ms debounce 之間 race < 1%
2. 真實 race 出現後，下次 click 嘅 revalidate 自動 sync 返
3. Production e-commerce 通常都係 D

**Sequential queue 唔啱 stepper UX 嘅根本原因：**用戶 expect 快速 stepper 嘅 server time 係 **invisible**。Sequential 將 server time 串連起 visible，違反 expectation。Sequential 適合 critical mutation（付款、提交），唔適合 ergonomic input。

---

### 9. Empty input UX trap —— `Number("") === 0`

```tsx
<input
  type="number"
  value={pendingQty}
  onChange={(e) => setPendingQty(Number(e.target.value))}
  // ❌ 用戶 backspace 清空 → e.target.value = "" → Number("") = 0 → setPendingQty(0)
  // → onBlur 觸發 commitQty(0) → 整行消失 😱
/>
```

**修法**：onBlur / onKeyDown 入面 guard `< 1` revert：

```tsx
onBlur={() => {
  if (pendingQty < 1) {
    setPendingQty(item.quantity);   // revert，唔係 commit
    return;
  }
  commitQty(pendingQty);
}}
```

**Mental model**：
- `−` button qty→0 = explicit "remove" intent ✅
- Input value=0 = ambiguous (typo / 半截清空) ❌

**Rule of thumb**：destructive actions (delete, cancel, remove) 要 **explicit user intent**，唔可以 inferred from `value === ""` / `value === 0` 呢類 ambiguous state。

---

### 10. 500ms debounce —— Sweet spot 嘅 perception research

| 時長 | UX | 業界用 |
|---|---|---|
| < 300ms | 用戶覺得「即時 sync」，快撳仍多 fire | rare |
| **300-500ms** | 平衡 perceived responsiveness + dedup | ✅ 主流 |
| > 1000ms | 感覺 sluggish，server confirm 太慢 | very rare |

500ms 對應**人類 conscious decision interval**：點 button 之後重新評估係咪要再點，~500ms 完成。再快嘅連 click 係 muscle memory burst，應該 dedup。再慢用戶等 server 失耐心。

實證：Amazon、Shopee、淘寶嘅 cart stepper 全部喺 300-500ms 範圍。

---

## 實戰反思

### 反思 1 —— 「Implementation correct ≠ observation matches expectation」

Part 2 debounce 完，Test Case「click + 1 then 即刻 -1」見到 server log `quantity=8` 仲係 fire 出去，估「debounce broken」。

實際：human reaction time + 滑鼠位移 = **typical 400-700ms**。「即刻」≠「<500ms」。Implementation 完全對。

**Test methodology lesson**：debounce / timing 嘅 verification 唔可以靠 human click rate。要：
- DevTools console fire `.click()` programmatically（< 1ms 間距）
- 或者 Playwright script
- 或者調 debounce time 短啲（e.g. 100ms）方便手動 reproduce

「我 test 嘅 case 真係係 < 500ms 內發生嗎？」呢個 timing assumption 要明確 verify，唔好假設「即刻」就係 zero。

---

### 反思 2 —— `useState(prop)` 唔自動 sync prop change

撞到呢段 logic 嘅時候：

```tsx
const [pendingQty, setPendingQty] = useState(item.quantity);
```

直覺以為 `item.quantity` prop 變，`pendingQty` 跟住變。但 `useState` initial value **只用 first render**，之後就 owned by component。

**確認方式**：reducer dispatch update 改咗 `optimisticItems`，CartRow 嘅 item.quantity prop 變，但 row 視覺上 0 反應。

**這件事嘅 implication**：
- 需要外部同步嘅 state 唔可以用 `useState(prop)` ——要用 `useEffect` + `setState`，或者 derive from prop（唔做 state）
- 我哋呢度刻意用 useState 隔離 → row 嘅 pendingQty 由用戶 click 主導，唔受 parent 干擾，呢個係 desired behavior

呢個係 React state 設計嘅一個分水嶺：**state 係 component owned 唔係 prop mirror**。

---

### 反思 3 —— Type union duplication 漏 sync

第一版實作 `CartOptimisticAction` 喺 CartRow + CartItemList 各自定義一份。Functional 上 work，但係：
- 改 schema 漏 sync 必發生
- TypeScript structural typing 之下唔會即時 catch 唔一致（兩個 type 結構同 = 可互換）

修法：搬去 `lib/cart/types.ts`，兩處 import。

**教訓**：Cross-file type 一定要 single source of truth。「我而家可以 grep 到兩個地方都改」嘅 mental commitment 太脆弱 —— 一個月後忘記。Compile-time enforcement > developer discipline。

---

### 反思 4 —— L31 transition rule 重新撞

L31 `placeOrder` action 要做 cookie delete + redirect 時撞過「optimistic update outside transition」error。L32 stepper button click 重撞同一個 error。

**發生根因**：`onClick` 唔係 transition 入面 default。React 19 對 useOptimistic dispatch 嚴格要求 transition context。

**解 again**：用 `startTransition` 包個 dispatch。Pattern 跟 L31 一樣。

**Rule 補充**：
- Form action callback → React auto-wrap ✅
- Action prop 傳給 server action → React auto-wrap ✅
- onClick / onChange / onBlur → 要手動 `startTransition` ❌

下次見到呢類 callback 入面 dispatch optimistic state，**第一念頭 wrap**。

---

## 實戰深入問題

### Q: 點解 `useState(item.quantity)` 喺 prop 變嗰陣唔會 sync？

`useState(initialValue)` 嘅 `initialValue` 喺 React 嘅 mental model 係：
> 「**第一次** render 時嘅初始值，**之後 React 完全 own 呢個 state**」

```tsx
function Row({ qty }: { qty: number }) {
  const [local, setLocal] = useState(qty);
  // local 由 first render 嗰刻 qty 决定
  // 之後 qty prop 點變都不影響 local
  return <span>{local}</span>;
}
```

如果 row 嘅 key 變（`<Row key={x} />`），React 視為 unmount + remount，`useState(qty)` 用新 qty。但 props update 嘅情況下，state 唔重置。

**要強制 sync prop 落 state，3 個 pattern：**
```tsx
// 1. 直接 derive（唔做 state）
const display = computeFrom(prop);

// 2. useEffect sync（小心無限 loop）
useEffect(() => setLocal(prop), [prop]);

// 3. key 重置（component-level reset）
<Row key={qty} qty={qty} />
```

我哋 cart row 故意**唔 sync** —— 用戶撳 button 改 local，唔想被 parent 干擾。

---

### Q: 點解 AbortController 唔解 race？

直覺：每次新 fetch fire 之前 `controller.abort()` 上一個。理應只剩最後一個 fetch 嘅 response。

**3 個問題：**

1. **Server 仍處理**
   `controller.abort()` 只係 client 唔等 response。Server 已收 request，**可能繼續執行 + 寫 DB**。對 PUT （idempotent）影響低；對 POST 危險。

2. **Network 已成本**
   Abort 之前 packets 已 send 出去，bandwidth 用咗。

3. **Last response wins ≠ correctness**
   即使 cancel 之前 4 個 fetch，最後一個 fetch 嘅 response 到時 **server state 可能已經被先到達嘅 fetch 寫過**。Server 順序：
   ```
   fetch1 (qty=8) → DB=8
   fetch2 (qty=9) → DB=9
   fetch3 (qty=10) → DB=10  ← cancel 咗都 server 都處理
   fetch4 (qty=11) → DB=11  ← only one 我哋讀到 response
   ```
   Race 由 server-side ordering 決定，AbortController 唔影響。

**真正解 race**：server-side optimistic concurrency control（version number / If-Match），或 sequential queue。但 cart quantity 通常**唔需要 race-free**（最後一個用戶意圖 wins，其他 lost 都 OK）。

---

### Q: Sequential queue 點解唔啱 stepper UX？

Sequential = 每個 mutation 等上一個完先 fire：
```
Click+ → debounce 500ms → fire1 (await 500ms response) → fire2 (await ...) → ...
```

5 click 用戶要等 **5 × 500ms response = 2.5 秒** 全部完成。

**核心問題**：用戶撳 stepper 嘅 mental model 係：
> 「點 + 即刻 +1，唔關心 server 幾時 confirm」

Sequential 將 server time 由 invisible 變 visible —— 用戶見到「我已經點完 5 次 + 但 UI 仲未反映 5」。Optimistic UI 嘅意義就係**hide server time**，sequential 對立呢個目標。

Sequential 啱用喺：
- 付款 / 提交訂單（critical，唔可以 lose write）
- 順序敏感嘅 mutation（A 必須喺 B 之前完成）

Stepper / cart UX 兩個都唔係 —— **fire-and-forget + revalidate** 完美夾。

---

## Practical Deliverable

### 改動 file 列表

**新文件 (1)**：
- `web/app/cart/CartRow.tsx` — Per-row stepper component

**修改文件 (2)**：
- `web/app/cart/CartItemList.tsx` — Reducer 升級成 multi-action union
- `web/lib/cart/types.ts` — 加 `CartOptimisticAction` shared type

**修改 line 數**：~120 lines

### Bug 時間軸

寫嘅過程撞嘅 bug，按出現順序：

1. **`<button>` 默認 `type="submit"`** — 喺 form 入面點 stepper button 自動 submit
2. **Empty input → 0 → 意外觸發 remove** — Backspace 清空再 blur，row 消失
3. **Optimistic dispatch outside transition** — 點 `−` 由 1→0 觸發 React error
4. **Stale closure on rapid click** — Closure `qty + 1` 多次點失準（修：functional updater）
5. **Initial useEffect 多 fire 一個 PUT** — 漏 `pendingQty === item.quantity` guard
6. **Type union duplicated** — CartRow + CartItemList 各定義一份
7. **Optimistic update dispatch 漏咗** — Reducer 寫咗 update case 但 commitQty update path 冇 dispatch（dead branch）
8. **Test methodology mistake** — 「即刻」嘅 click rate >500ms，誤以為 debounce broken

### 驗證 checklist

- [x] Click `+` 5 次快速（< 500ms 之間）→ backend 1 條 PUT log，`quantity=initial+5`
- [x] Click `+` 1 次，等 1 秒 → 1 條 PUT log
- [x] Click `+` 後即刻 `−`（程式化 < 500ms）→ 0 條 PUT log（pendingQty === item.quantity，return early）
- [x] Type `5` 入 input + tab/Enter → 1 條 PUT log，`quantity=5`
- [x] Backspace 清空 input + tab → revert 返 item.quantity，0 PUT
- [x] Click `−` 由 qty=1 變 0 → row 即時消失（optimistic remove）+ DELETE log
- [x] 多個 row 各自 stepper —— 互不影響
- [x] 點 Remove button → row 即時消失 + DELETE log

### L32 留底（為將來鋪墊）

- **Race condition**：accept trade-off，等 L33 SSE / WebSocket 解決
- **Cart total in header optimistic**：而家靠 revalidate，未經 `optimisticItems` aggregate
- **Mobile redesign**：bonus 跳咗，留 L33+
- **Loading feedback (`useTransition`)**：bonus 跳咗，stepper 而家已經 instant local feedback，視覺 OK
- **Stock check error handling**：bonus 跳咗，stepper 超過 stock 仲會 fire 然後 backend reject，error boundary 接（uglier UX）
