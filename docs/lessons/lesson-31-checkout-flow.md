# Lesson 31: Checkout Flow + Async Saga UX + Form State Management

**Date:** 2026-04-26 ~ 2026-04-28

L30 完成 unified cart + token expiration handling，cart 路徑兩端打通。L31 接住做 **checkout / order flow** —— 表面係加幾條 backend route 對應嘅 frontend page，但因為 backend `POST /api/orders/checkout` 係 **async (Kafka + Saga)**，HTTP response 同真實 order 創建之間有 latency，呢個 architecture 直接影響前端 redirect 邏輯、error handling、UX 設計。

完整功能：`/checkout` 頁、`placeOrder` server action、`/orders` list (with status filter)、`/orders/[id]` detail (with cancel)、`<StatusBadge>` reusable component、`useActionState` inline form errors、Saga in-flight banner。

⚠️ **Next.js 16 reminders** —— 兩個 breaking changes 喺呢堂全部撞到：
- `params` 由 plain object 變 `Promise<{...}>`，dynamic route 必須 `await params`
- `searchParams` 同樣係 `Promise<{...}>`，要 await

---

## 核心概念

### 1. Async backend (202 ACCEPTED) —— Frontend 點 hand-off

Backend `POST /api/orders/checkout` 嘅實際行為：

```java
public void checkout(OrderRequest request) {
    OrderEvent event = new OrderEvent();
    orderMessageProducer.sendOrderMessage(event, command);  // Kafka 送出去
    // return 202 ACCEPTED + {"message": "Order is being processed"}
}
```

真實創建 order 喺 Kafka consumer (`doCheckout()`) 入面跑 4 步 Saga：`VALIDATE_CART → DEDUCT_STOCK → CREATE_ORDER → CLEAR_CART`。

**對前端嘅 implication**：HTTP response 已經 return 202，**冇 order ID**，**亦唔知 Saga 結果**。所以「checkout 完跳 `/orders/{id}`」呢個傳統 pattern **做唔到** —— 我哋根本攞唔到 ID。

兩條 hand-off 路：
- ❌ **B 方案 (timing assumption)**：前端 sleep 1 秒，再 GET `/api/orders` 攞最新嗰張 → redirect `/orders/{id}`。Kafka 慢過 1 秒就斷
- ✅ **A 方案 (eventual consistency UX)**：直接 redirect `/orders` list，list 自己 query 最新 state；Saga 跑緊就暫時見唔到，跑完 refresh 就有

L31 揀 A，因為 A 唔做 timing assumption。distributed system 嘅 client 永遠唔可以猜 backend 處理時間。

---

### 2. Server Action return state vs throw —— `useActionState` paradigm

L31 之前嘅 server actions（`addToCart` / `placeOrder` 第一版）失敗就 `throw new Error(...)` → 跌到 React error boundary → 用戶見到 ugly default error page、form 唔見咗。

`useActionState` 嘅核心係：**action 用 return value 同 client component communicate state**，唔再 throw。

```ts
export type CheckoutState = { error: string | null };

export async function placeOrder(
  prevState: CheckoutState,        // ← 上次 action 嘅 return
  formData: FormData,
): Promise<CheckoutState> {
  if (typeof raw !== "string" || raw.trim().length < 5) {
    return { error: "Shipping address must be at least 5 characters" };  // ← state，唔係 throw
  }

  // ... fetch ...
  if (!res.ok) return { error: errBody || "Checkout failed" };

  redirect("/orders?placed=1");  // 成功 = redirect，冇 return
}
```

Client 一邊：

```tsx
"use client";
const [state, formAction, isPending] = useActionState(placeOrder, initialCheckoutState);

return (
  <form action={formAction}>
    <textarea name="shippingAddress" disabled={isPending} />
    {state.error && <p className="text-red-600">{state.error}</p>}
    <button disabled={isPending}>{isPending ? "Placing..." : "Place Order"}</button>
  </form>
);
```

**Mental model**：Action 變咗 reducer，`(prevState, formData) → newState`。同 useReducer 一樣 paradigm。

---

### 3. `"use server"` file —— 只可以 export async function

```ts
"use server";

export async function placeOrder(...) { ... }    // ✅
export type CheckoutState = { ... };              // ✅ type 唔算 runtime export
export const initialCheckoutState = { ... };     // ❌ 報錯
```

`"use server"` directive 嘅意思：「呢個 file 入面所有 export 都係 Server Action」。Server Action 有 RPC semantics，必須係 `async function` 先 serialize 到。

撞到呢個 runtime error：
```
Only async functions are allowed to be exported in "use server" files.
```

修法：將 type 同 const 搬去 sibling file（`lib/orders/types.ts`），actions.ts 用 `import type` import 返。

對應嘅 cousin rule：`"use client"` file **唔可以 import server-only modules**（`next/headers`、direct DB client）。兩條 boundary 規則合埋 = RSC 嘅 contract。

---

### 4. Dynamic route + `await params` —— Next.js 16 breaking change

```tsx
// ❌ Next.js 14
export default function Page({ params }: { params: { id: string } }) {
  const id = params.id;
}

// ✅ Next.js 15+ / 16
export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
}
```

點解？Next.js 想 streaming SSR —— page 開始 render 時 URL params 未必 ready，改成 Promise 就可以 deferred-await。

⚠️ 忘記 await 嘅後果：TypeScript 唔 compile（type 唔 match），runtime `params.id` 變 `undefined`（你 access 緊 Promise object 嘅 property）。

對應：`searchParams` 同樣由 plain object 變 Promise：
```tsx
{ searchParams }: { searchParams: Promise<{ status?: string }> }
const { status } = await searchParams;
```

---

### 5. URL string passthrough —— Frontend 唔需要 type conversion

URL `/orders/123` 入面個 `123` 係 string。但 backend `cancelOrder(@PathVariable Long orderId)` 食 Long。問：點處理？

答：**唔需要前端 convert**。
```ts
fetch(`${BACKEND_URL}/api/orders/${id}/cancel`)   // id 係 string "123"
//                              ↑
//                   Backend Spring 自動 parse 成 Long
```

Spring `@PathVariable Long` 自己負責 parse。前端 string passthrough 就 enough，**conversion 喺 backend 做**。

呢個 mental model 重要 —— 前後端 type bridge 嘅 default 係 **string serialization**（HTTP URL / body 都係 text）。Backend framework 提供 type coercion；frontend 只要 produce 對嘅 string。

---

### 6. Literal union narrowing + `Record<Enum, T>` exhaustiveness

```ts
// ❌ string 太鬆
type Order = { orderStatus: string };

// ✅ Literal union narrow
export type OrderStatus =
  | "PENDING_PAYMENT" | "PAID" | "SHIPPED" | "DELIVERED"
  | "COMPLETED" | "RETURNED" | "CANCELLED";

export type Order = { orderStatus: OrderStatus };
```

`status === "PNDING_PAYMENT"`（typo）原本 string compile 通過，narrow 後 TypeScript 即時報錯。

`Record<OrderStatus, T>` 強迫所有 enum case 都有 entry：

```tsx
const STATUS_STYLES: Record<OrderStatus, { label: string; className: string }> = {
  PENDING_PAYMENT: { label: "Pending Payment", className: "bg-amber-100 text-amber-800" },
  PAID:            { label: "Paid",            className: "bg-blue-100 text-blue-800" },
  // ... 漏一個 → compile error
};
```

**Trade-off**：backend 加新 status 時 frontend 要 sync，但呢個就係 type safety 嘅好處 —— compiler 攔截「漏 sync」嘅 bug。

對比 TS `enum`（runtime object）vs literal union（compile-time only）：

| | enum | Literal union |
|---|---|---|
| Runtime 開銷 | 編譯成 JS object | 0 |
| Tree-shake | ❌ | ✅ |
| 現代 React/Next 慣用 | 唔流行 | ✅ |

L31 用 literal union，bundle 細啲，現代 idiomatic。

---

### 7. Server / Client component split —— 邊度劃線

`/checkout/page.tsx` 讀 cookies + fetch backend cart → **Server Component**。
`<CheckoutForm>` 用 `useActionState` hook → **Client Component** (`"use client"`)。

```tsx
// page.tsx (server)
export default async function CheckoutPage() {
  const cart = await getCart();              // ← server-only
  if (cart.items.length === 0) redirect("/cart");

  return <main>{/* receipt */} <CheckoutForm /></main>;  // ← 嵌入 client
}

// CheckoutForm.tsx (client)
"use client";
export default function CheckoutForm() {
  const [state, action] = useActionState(placeOrder, initialCheckoutState);
  return <form action={action}>...</form>;
}
```

**Why**：所有 React hook (`useState`/`useEffect`/`useActionState`) 喺 client 上行；server component 係 render-once、無 state。Form 要 hook → client；page 讀 cookies → server。

呢個 split 唔需要 prop drilling —— `<CheckoutForm>` import server action 直接 call，唔需要 page 傳 callback。

---

### 8. Defense in depth —— 多層 redirect / validation

`/orders` page 嘅 token check：
- Page 自己 `if (!token) redirect("/login?next=/orders")` —— UX 友好
- `getMyOrders()` helper throw `"Not authenticated"` if 內部 check 失敗 —— fail-fast，唔會悄悄拎錯數據
- `authFetch` 接到 401 throw `TokenExpiredError` —— page 接住再 redirect

3 層唔重複，係互補：

| 層 | 職責 |
|---|---|
| Page token check | 用戶友好嘅 redirect |
| Helper throw | 防 page 漏 check 嘅 fail-fast |
| Backend 401 + TokenExpiredError | Token 壞嗰陣由 helper 觸發 |

Cancel button 同樣 defense in depth：
- Frontend `CANCELLABLE_STATUSES.includes(...)` hide button —— 防誤點
- Backend `OrderService.cancelOrder()` 再 check status —— 防 API direct call
- 前端 hide 唔等於 security，係 UX。Backend 永遠係 source of truth

---

### 9. Cancel mutation pattern —— Hidden input + form action

冇 JS 都要 work + 唔需要 client component：

```tsx
{canCancel && (
  <form action={cancelOrder}>
    <input type="hidden" name="orderId" value={order.id} />
    <button type="submit">Cancel Order</button>
  </form>
)}
```

```ts
export async function cancelOrder(formData: FormData) {
  const raw = formData.get("orderId");
  if (typeof raw !== "string") throw new Error("Invalid order ID");
  // ... fetch PUT /api/orders/{raw}/cancel ...
  revalidatePath("/orders");
  revalidatePath(`/orders/${raw}`);
}
```

**Hidden input** = 將 row data（呢度係 orderId）藏入 form，提交時自動入 FormData。Server action 唔需要受 prop（佢淨 receive `FormData`），呢個係**最簡單 row-level mutation pattern**。

---

### 10. Generic vs specific naming + DRY config

L30 留低嘅 anti-pattern：
```ts
// 5 個 file 各自重複呢段
const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";
```

兩個 issue 糾纏：
1. **DRY violation** —— 改 port 要改 5 個 file
2. **Security smell** —— `NEXT_PUBLIC_*` prefix 將 backend URL inline 入 client bundle，但呢 5 個 file 全部 server-only，0 必要暴露
3. **Generic naming** —— `API_BASE_URL` 將來想接多個 backend (payment / analytics) 會撞名

L31 同步 refactor：
```ts
// web/lib/config.ts
export const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";
```

5 個 file 統一 import。改名 `BACKEND_URL` —— grep 即時知意思，**「為將來嘅自己預留 grep 空間」**。

---

## 實戰反思

### 反思 1 —— Try/catch scope creep

`/orders/page.tsx` 第一版：
```tsx
let orders: Order[];
try {
  orders = await getMyOrders();

  if (orders.length === 0) return (...);     // ← 喺 try 入面
  const { status } = await searchParams;     // ← 喺 try 入面
  // ... filter + render ...
  return (<main>...</main>);                  // ← 喺 try 入面
} catch (err) {
  if (err instanceof TokenExpiredError) redirect(...);
  // 冇 throw err! 其他 error silently swallowed
}
```

兩個 anti-pattern：
1. Try 包到太闊 —— filter / render 邏輯被 catch 包住，任何 render bug 被吞
2. 冇 `throw err` —— 唔係 TokenExpiredError 嘅 error 全部消失，function return undefined，Next 報「render error」但你冇線索

修法：try 只 wrap fetch，跟住 phase 清晰分開：
```tsx
let orders: Order[];
try { orders = await getMyOrders(); }
catch (err) {
  if (err instanceof TokenExpiredError) redirect(...);
  throw err;     // ← 必須 re-throw 其他
}
// 然後 render 喺 try 外面
```

呢個係 **「catch the error you can handle, throw the rest」** principle —— 唔係 try/catch 嘅錯，係 scope 同 throw 嘅 discipline。

---

### 反思 2 —— Convention drift（`stores.ts` vs `store.ts`）

L30 cart 嘅命名 `lib/cart/store.ts`（單數）。L31 寫 orders helper 時打成 `stores.ts`（複數）—— 兩個 lesson 嘅 convention drift。

仲有 OrderCard 一開始 `import {components} from "@/lib/api/types"` 用 OpenAPI auto-generated type，但 `getMyOrders` return 嘅係手寫 `Order` type —— 兩個 source of truth 並存，將來改 schema 漏 sync 必定發生。

**Convention consistency > 個人偏好**。Repo 已經建立咗嘅 pattern，新 file 應該 mirror，唔係按 case-by-case taste 寫。

教訓：開始寫新 file 之前，**grep 一次相鄰 module 嘅命名 / structure**。`grep "store.ts$" web/lib/` 兩秒可以避免呢類 drift。

---

### 反思 3 —— Mutation discipline 漏 revalidate

`cancelOrder` 第一版漏 `revalidatePath` 收尾，導致 cancel 後 list / detail page 仍然顯示 cached `PENDING_PAYMENT`，用戶以為「點咗冇反應」。

L30 學咗：

> Mutation 之後**一定**要 revalidate 受影響 paths。

L31 重新撞 + 修，pattern 內化中。但「revalidate 邊個 path」要思考清楚 —— L31 cancel 涉及 list + detail，所以兩個都要 revalidate。

呢個係 mutation 後嘅 mental checklist：
1. 邊個 page 顯示緊呢個 resource？全部 revalidate
2. 邊個 component 嘅 derived state 受影響？（e.g. cart count、order count）一併 revalidate
3. 用 template literal 拼動態 path：`` revalidatePath(`/orders/${orderId}`) ``

---

### 反思 4 —— 接受 distributed system 嘅 fundamental limit

撞到 Saga failure case：
- Frontend POST → 202 → 跳 `/orders`
- ASYNC: Saga Step 2 fail (`Product is not on sale`) → rollback
- 用戶見唔到 error，list 入面冇新 order，cart 仲有嘢 —— 完全唔知發生咩事

呢個唔係 frontend bug，係 **HTTP request/response 模型嘅 fundamental constraint** —— 一旦 server 已 return，就冇渠道再通知 client。

L31 揀 pragmatic 解決：
1. Banner on `/orders?placed=1` —— 提示「處理緊」+ 提到「可能失敗」
2. Lesson notes 標記 known limitation
3. 真正完整方案（SSE / WebSocket / polling）留 L33+

呢個態度本身就係 lesson —— **distributed system 嘅 honesty**。承認 trade-off，揀啱 abstraction layer 嘅解。

---

## 實戰深入問題

### Q: "點解 frontend 冇辦法同步知 Saga 失敗？"

時間線：
```
T=0   Frontend POST /api/orders/checkout
T=10  Backend send Kafka message → return 202
T=11  res.ok = true
T=12  Frontend redirect("/orders")
                ⏬⏬⏬
T=50  Saga consumer 開始
T=70  Step 2 fail，rollback
T=71  Frontend 已經消失咗
```

HTTP response 係 **single shot** —— server 一寫完 response body 就斷 connection。Kafka consumer 係**之後**先觸發 `doCheckout()`，但 frontend 完全唔知。

要 frontend 知道，要 4 種 channel 之一：
- **Polling** —— frontend 每 N 秒 GET `/api/orders` 等出現 / fail
- **SSE** —— backend 透過 long-lived HTTP push status
- **WebSocket** —— 雙向 channel，consumer 完成時 emit event
- **Email / Push** —— out-of-band，唔需要前端 connection

選擇取決於 latency 要求 + UX 期望 + 工程成本。L31 唔做 channel，純粹 banner 提示「可能未完成」。

---

### Q: "點解 `useActionState` 一定要 client component？"

所有 React hook 都喺 **client runtime** 跑：
- `useState` 需要 reactive state container
- `useEffect` 需要 lifecycle hook
- `useActionState` 需要 form state container + RPC bridge to server

**Server Component** 係 pure render-once function：
- 冇 lifecycle
- 冇 state container
- Render 完即返 HTML/RSC payload，唔再執行

如果 server component 用 `useState`，React runtime 即時報錯。
`useActionState` 同樣 —— 佢需要 client 上嘅 state machine + server action RPC stub。

呢個係**為咩 RSC architecture 要 explicit 標 `"use server"` / `"use client"`** —— 兩邊執行環境唔同，編譯器要靠 directive 知邊個 file 入邊個 bundle。

---

### Q: "Banner vs Optimistic UI vs Polling —— 點揀？"

| Pattern | 點 work | UX | 工程成本 |
|---|---|---|---|
| **Banner** (L31 揀) | URL `?placed=1` 顯示 generic message | 🟡 用戶要 refresh / 等 | ⭐ |
| **Optimistic UI** | `useOptimistic` 即時插假 entry | ✅ 立即見到「Pending」card，Saga 完真實 entry 取代 | ⭐⭐ |
| **Polling** | client `setInterval` 每 3 秒 refresh | ✅ 自動見最新 | ⭐⭐ |
| **SSE / WebSocket** | Backend push 真實 status | ✅✅ 完美實時 | ⭐⭐⭐⭐ |

L31 揀 banner 因為：
- Gold (`useActionState`) 已經教咗新 hook
- Optimistic UI + polling 各自需要新概念，再塞會稀釋 lesson focus
- Banner 5 分鐘 implement，效果 acceptable 對 dev 環境
- Production-grade real-time 留 L33+ 做完整 system

**漸進複雜度** > 一次教曬。

---

## Practical Deliverable

### 改動 file 列表

**新文件 (10)**:
- `web/app/checkout/page.tsx` — server component (cart summary + redirect if empty)
- `web/app/checkout/CheckoutForm.tsx` — client component (`useActionState`)
- `web/app/orders/page.tsx` — list page (filter + banner)
- `web/app/orders/[id]/page.tsx` — detail page (cancel button)
- `web/app/_components/OrderCard.tsx` — list item card
- `web/app/_components/StatusBadge.tsx` — color-coded status pill
- `web/lib/orders/store.ts` — `getMyOrders` + `getOrderDetail`
- `web/lib/orders/actions.ts` — `placeOrder` + `cancelOrder`
- `web/lib/orders/types.ts` — `Order` / `OrderItem` / `OrderStatus` / `CheckoutState`
- `web/lib/config.ts` — centralized `BACKEND_URL`

**修改文件 (6)**:
- `web/lib/api/client.ts` — use `BACKEND_URL` from config
- `web/lib/cart/store.ts` — use `BACKEND_URL` from config
- `web/lib/cart/actions.ts` — use `BACKEND_URL` from config
- `web/lib/cart/migrate.ts` — use `BACKEND_URL` from config
- `web/lib/auth/actions.ts` — use `BACKEND_URL` from config
- `web/app/cart/page.tsx` — add `<Link href="/checkout">` CTA

### Bug 時間軸

寫嘅過程撞嘅 bug，按出現順序：

1. **Form structure broken** — `<textarea>` 喺 `<form>` 外面 → submit 永遠 empty FormData
2. **`stores.ts` (plural)** — convention drift，應該係 `store.ts`
3. **`getMyOrders` return type 錯** — `Promise<Order>` 應該係 `Promise<Order[]>`
4. **`token!` non-null assertion** — 欺騙 TS，runtime 仍可 undefined
5. **`return {}` 空物件** — fetch 完冇 return data
6. **OrderCard 用錯 type 來源** — OpenAPI auto-gen vs hand-written `Order`
7. **JSX `${var}` 同 template literal 混淆** — `<p>${order.orderStatus}</p>` 顯示 `$PENDING_PAYMENT`
8. **Page title "All Products"** — copy-paste leftover
9. **Auth check 漏咗** — `/orders` 直接 throw error 唔 redirect login
10. **Token tampered ≠ token missing** — page 只 handle 第二種
11. **`cancelOrder` 漏 revalidatePath** — cancel 後 list / detail stale
12. **Try/catch wrap 太闊** — orders/page.tsx 包住 render，吞 non-token errors
13. **Filter pill 漏 `)`** — `All ({orders.length}` 顯示 `All (5`
14. **`"use server"` 不能 export const** — `initialCheckoutState` 要搬走

每個 bug 都對應一個 mental model 漏洞。修一次 + 寫入反思 = 內化。

### 驗證 checklist

- [x] Logged-in user：`/checkout` → submit → `/orders?placed=1` → 見 banner + 新 order
- [x] Empty cart 入 `/checkout` → redirect `/cart`
- [x] Anonymous 入 `/checkout` / `/orders` → redirect `/login?next=...`
- [x] Tampered token → redirect login（唔係 error page）
- [x] Cart empty 時 backend reject (Saga fail) → 跳 `/orders` 見 banner，list 入面冇新 order
- [x] `/orders` filter pill click → URL `?status=...` + filter work
- [x] Cancel pending order → 即時見 `CANCELLED` badge + button 消失
- [x] Cancel non-cancellable order → button 唔顯示
- [x] Form validation < 5 字 → 留喺 page，inline `state.error` 顯示
- [x] Submit 中 button + textarea disable，顯示「Placing order...」
