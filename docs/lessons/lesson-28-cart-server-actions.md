# Lesson 28: Cart CRUD via Server Actions + Optimistic UI

**Date:** 2026-04-22

L27 掌握咗 JSX / components / navigation。今課深入 **mutation primitives** —— 用 **Server Actions** 建完整 cart CRUD（add / read / update / remove / clear），加 React 19 嘅 **`useOptimistic`** hook 做即時 UI feedback。

完整功能：Cookie-backed guest cart（唔 depend backend auth）、4 個 server actions（`addToCart` / `removeFromCart` / `updateQuantity` / `clearCart`）、`/cart` 頁 CRUD UI、Header 🛒 badge 接真 count、remove 有 optimistic instant feedback。

⚠️ **Next.js 16 Reminder** — `cookies()` 係 async；Server Actions 預設 `"use server"` directive；寫 code 前睇 `web/AGENTS.md`。

---

## 為咩用 cookie 唔直接接 backend cart API？

Backend L6 已有 `/api/cart/*` endpoints，但**要 login** 先用得（`CartService.getCurrentUser()` 從 `SecurityContextHolder` 攞 user）。L28 而家未接 auth（L29 先做）—— 所以呢課刻意選擇 **cookie-based guest cart**：

| Option | Trade-off |
|---|---|
| **Cookie-backed (今課用)** | ✅ 繞過 auth，focus 純 Next.js mutation primitives / ⚠️ ~4KB limit / ⚠️ 跨 device 唔 sync |
| **localStorage** | ✅ 容量大 5MB / ❌ Server 讀唔到 → SSR 白畫面 flash、Header badge 要 extra round trip |
| **Database (user_id)** | ✅ Persistent / ❌ 要 auth、L29 先做 |

**Real-world pattern** — Amazon、Shopify 都支持 guest cart，login 時再 migrate 過 backend（L29 會做）。

---

## 核心概念

### 1. Server Actions — server function 喺 client 度 call

**Server Action** = 一啲 function 標記咗 `"use server"` directive，可以由 client component 直接 call，但 body 喺 server 行。

```tsx
// 🔵 Client component (browser)
"use client";
import { addToCart } from "@/lib/cart/actions";

<button onClick={() => addToCart(1, 2)}>Add</button>
//                  ^^^^^^^^^^^ Next 偷偷換成 POST request
```

```ts
// 🟢 Server function (Node runtime)
"use server";
export async function addToCart(productId: number, quantity: number) {
  const store = await cookies();     // 可以 access cookies / DB / secrets
  store.set("cart", ...);
}
```

**Magic under the hood：**

1. Compile time — Next 見到 `"use server"` file → 自動為每個 export 生成 hidden HTTP endpoint（路徑 like `/_next/...`）
2. Import 去 client component 時 — Next 用 client-side RPC wrapper 代替 real function
3. Runtime — Client call `addToCart(1, 2)` → wrapper serialize args → POST → server run real function → return serialized result

**對比 REST API route：**

| | REST API Route | Server Action |
|---|---|---|
| 要寫 `app/api/cart/route.ts` | ✅ | ❌ |
| Client 要 `fetch(...)` + `JSON.stringify` | ✅ | ❌ |
| Server 要 `await req.json()` parse | ✅ | ❌ |
| Types 要手動 sync | ✅ | ❌（同一 TS file import） |
| Form `<form action={...}>` | 自己寫 handler | ✅ built-in |
| 通用俾 non-JS client（mobile、curl） | ✅ | ❌（RSC-specific） |

---

### 2. `"use server"` directive vs `"use client"`

呢兩個 directive 係 **file-level**（寫喺最頂），決定成個 file 裡面 exports 嘅執行環境：

| Directive | 含義 | 限制 |
|-----------|------|------|
| `"use client"` | 所有 exports 打包入 client bundle（browser 行） | 可用 hooks、event handlers；**唔可以** 用 `cookies()` / DB / env secrets |
| `"use server"` | 所有 exports 變 server actions | 唔可以 return function / Date 等 non-serializable；**必須 async** |

**兩個 directive 都係 directive（string literal，非 import）**。放喺 file 第一行（import 之前）。

```ts
// 正確
"use server";
import { cookies } from "next/headers";
export async function addToCart(...) { ... }
```

```ts
// 錯：directive 喺 import 後面冇效
import { cookies } from "next/headers";
"use server";
```

---

### 3. `cookies()` API — Next 16 async

```ts
const store = await cookies();          // ⚠️ Next 16 變 async

// Read
store.get("cart")?.value                 // returns string | undefined

// Write (only in Route Handlers / Server Actions)
store.set("cart", JSON.stringify(x), {
  path: "/",
  maxAge: 60 * 60 * 24 * 30,            // 30 days in seconds
  httpOnly: false,                       // default false，要 client 讀就 false
  sameSite: "lax",                       // default lax，CSRF 保護
});

// Delete
store.delete("cart");                    // equivalent 於 set with maxAge=0
```

**Gotcha：** Server component render 期間只可以 **read** cookie，**唔可以 set / delete**。想 mutate cookie 必須喺 **Server Action** 或 **Route Handler** 入面。違反會 throw。

**原因：** HTTP response headers（Set-Cookie 喺度）喺 render 開始後就 lock 咗，render 中途想改 header 已經太遲。

---

### 4. `revalidatePath` — invalidate cached render

```ts
import { revalidatePath } from "next/cache";

revalidatePath("/cart");
```

作用：**將 `/cart` 路徑嘅 cached HTML / RSC payload mark 做 stale**，下次 request 會重新 render。

**點解 cart mutation 要 `revalidatePath`？**

- Action 改咗 cookie → `/cart` page 下次 render 應該見到新 items
- 冇 `revalidatePath` → Next 可能攞 stale HTML（特別係 static / ISR route）
- 有 `revalidatePath` → guaranteed fresh render

**範圍：** `revalidatePath("/cart")` 都會 invalidate **root layout**（包括 `<Header />`），所以 cart badge 都會更新。呢個係 L28 🥈 exercise 嘅 key insight。

**仲有 `revalidateTag`** — 更 fine-grained invalidation，用 tag 標 fetch，mutation 時 invalidate 嗰 tag。今課未用。

---

### 5. `<form action={serverAction}>` — 零 client JS pattern

Server Action 可以喺 **server component** 直接用（唔使 `"use client"`）：

```tsx
// Server component —— 冇 'use client'
import { clearCart } from "@/lib/cart/actions";

<form action={clearCart}>
  <button type="submit">Clear cart</button>
</form>
```

**呢個 pattern 嘅好處：**

- **零 client bundle** — 整個 page 零 JS 落 browser，除咗 Next 少少 plumbing
- **Progressive enhancement** — JS disabled 都 work（native form submit）
- **Simpler code** — 冇 `"use client"`、冇 useState、冇 onClick

**點 flow：** 按 submit → browser 發 POST form → Next 認到個 form action 係 Server Action reference → call action → action 做 mutation + revalidate → page re-render → browser navigate 返新 HTML。

---

### 6. `.bind()` 預先 supply 參數 + FormData API

Server Action 喺 `<form action>` 下 run 時，Next 會**最後 auto-append 一個 `FormData` argument**。要傳 productId 等 dynamic value，有 2 個 approach：

**Approach A — `.bind()`** （static args 用呢個）：

```tsx
<form action={removeFromCart.bind(null, item.productId)}>
//                           ^^^^  ^^^^^^^^^^^^^^^
//                           this   1st arg pre-filled
  <button>Remove</button>
</form>

// Action signature (no FormData param needed if binding covers all args)
export async function removeFromCart(productId: number) { ... }
```

**Approach B — FormData `name="..."` inputs**（dynamic user input 用呢個）：

```tsx
<form action={updateQuantity.bind(null, item.productId)}>
  <input name="quantity" type="number" defaultValue={item.quantity} />
  <button>Update</button>
</form>

// Action — FormData 一定係 LAST arg
export async function updateQuantity(productId: number, formData: FormData) {
  const quantity = Number(formData.get("quantity"));       // 永遠係 string，要 cast
  // ...
}
```

**`.bind()` 係 partial application** —— JavaScript 一路都有嘅 `Function.prototype.bind`：
```ts
function add(a: number, b: number) { return a + b; }
const add5 = add.bind(null, 5);    // a = 5 預設
add5(3);                            // 8
```

**Gotcha — FormData `.get()` 返 `string | File`**，一定要：
```ts
const quantity = Number(formData.get("quantity"));
if (!Number.isFinite(quantity)) { /* handle NaN */ }
```

---

### 7. `useOptimistic` — 即時 UI，server 跑緊

React 19 hook。**俾 UI 顯示「假設 server 會成功」嘅版本**，background mutation 繼續跑，失敗時 auto-rollback。

```tsx
"use client";
import { useOptimistic } from "react";

const [optimisticItems, applyOptimistic] = useOptimistic(
  items,                                    // 真實 server state
  (current, removedId: number) =>           // reducer
    current.filter(i => i.productId !== removedId)
);

// Form submit
<form action={async () => {
  applyOptimistic(item.productId);          // 1. UI 即時 update
  await removeFromCart(item.productId);     // 2. Background server action
}}>
```

**Reducer pattern：**

- 第 2 個 argument 係 reducer `(currentState, action) => newState`
- Call `applyOptimistic(action)` 時 → React 用 reducer 計 new state → re-render
- Server mutation finish + revalidate → `items` 更新 → `useOptimistic` 同步新 base state

**Auto-rollback：** Server action throw 時，React discard optimistic state，revert 返 real state。不需要手寫 rollback logic。

**⚠️ `useOptimistic` 係 hook → 只可以用喺 client component**，所以而家 `/cart/page.tsx`（server component）入面嘅 items list 要 **extract** 去新 client component `CartItemList.tsx`。

**⚠️ Scope 嘅 trade-off：** Optimistic state 同 real state 可能 drift。今課只 optimistic 咗「items list filter」，**subtotal 留返 server 計**（等 revalidate 後更新）—— 因為 subtotal 計算重複度高，唔值得 mirror。Optimistic 應該只用喺 **簡單、可逆、容易 rollback** 嘅 UI change。

---

### 8. Server / Client boundary refactor pattern

呢個 pattern 今課撞過 2 次：

**Case 1 — `AddToCartButton`（L27 延續）：**

Server page `/products/[id]/page.tsx` 要 render 一個 interactive button（要 `useState`、`onClick`）。解法：**extract 個 button 做 client component**，server page pass serializable props：

```tsx
// Server page
<AddToCartButton
  productId={product.id!}      // ✅ number — serializable
  stock={product.stock ?? 0}   // ✅ number — serializable
/>
```

**Case 2 — `CartItemList`（今課 🥇 做）：**

Server page `/cart/page.tsx` 想喺 items list 上用 `useOptimistic`。解法：extract 整個 items list 去 client component：

```tsx
// Server page
<CartItemList items={items} products={products} />
//            ^^^^^^^       ^^^^^^^^^^^^^^^^^
//            plain arrays — serializable ✅
```

**Props boundary rule：** 只可以 pass **JSON-serializable** data（primitive、plain object、array）。**function / Date / class instance / Map / Set 過唔到**。

---

## 實戰反思

### 反思 1 — `.next/` cache corruption 診斷

今課 restart dev server 後：`/` work、`/products`、`/products/1`、`/cart` 三個 route **齊齊 404**。Code 冇改，file 都仲喺度。

**診斷 step by step：**

1. Backend 掛咗？`curl localhost:8080/api/products/1` → 200，backend OK
2. File 刪咗？`ls web/app/products/[id]/` → page.tsx 喺度
3. Code syntax error？Read file → clean
4. `curl localhost:3000/products/1` 攞 HTML response → 見到 `"c":["","products","1"]` + `"children":["/_not-found"]` → Next **resolve 唔到** `[id]` route

**Root cause：** Turbopack / Next dev server 嘅 `.next/` cache 同 in-memory route registry 唔同步。Restart 清 memory，**但唔清 `.next/`**，stale manifest 令某啲 route 消失咗。

**Fix：**
```bash
cd web
rm -rf .next
npm run dev
```

**教訓：** Next 有 **2 層 cache**：memory（restart 清）+ `.next/` disk（restart **唔**清）。見到「file 明明啱但 route 消失」第一反應清 `.next/`。觸發場景：file rename、folder 改動、Node version 變、plugin 更新後。

### 反思 2 — Browser extension 令 hydration error

Dev console 出：
```
- data-sharkid="__0"
(server had this attribute, client didn't)
```

`data-sharkid` 唔係我哋 code 嘅產物，係 **browser extension（某個 form-filler / password manager）注入**。流程：
1. Server send HTML → browser 收
2. Extension **喺 React load 前** 偷偷改 DOM（加 data-attr）
3. React hydrate 時比對 server HTML vs 自己計算出嚟嘅 tree → mismatch → warn

**Verify method：** Incognito window 試（extensions 通常 disabled）→ 冇 error = 確認 extension 污染。

**教訓：**
- 唔好手動 `suppressHydrationWarning` 靜音（會 suppress 真 bug）
- Prod 用戶睇唔到呢個 warning（dev only）
- Next error message 自己提醒有呢個 case，讀嘢之前認清 warning 源頭

### 反思 3 — Optimistic UI scope 嘅 trade-off

Hard exercise 做完，Remove button click **即時消失** ✅，但 **subtotal 延遲 ~1 秒** 先更新。

呢個係 **intentional trade-off**，唔係 bug：

- Items list filter 簡單（1 line reducer），optimistic 俾就得
- Subtotal 計算重複（reduce over items + 查 products[idx].price）；如果 optimistic state 同 real state drift，容易計錯
- **原則：optimistic 只用喺「容易 mirror / 簡單可逆」嘅 UI change**

Real-world 產品經常都咁做 —— Twitter 點 like 即時變紅但 count 有 slight delay、Gmail archive 即時消失但 folder count 延遲。

### 反思 4 — Cookie-based guest cart 係設計決定，唔係妥協

Backend 明明有 `/api/cart` endpoints，但今課特登繞過 —— 因為：

1. Backend cart 要 login，但 auth 留到 L29
2. Next.js 嘅 mutation primitives（Server Actions、cookies、revalidatePath、useOptimistic）係 L28 教學核心 —— 接 backend 會拖累
3. Guest cart 係 real e-commerce pattern（Amazon、Shopify）

L29 會做 auth + **cart migration**（login 時將 guest cart merge 去 user cart），到時 backend API 就接得返。呢個係 proper feature progression。

---

## 實戰深入問題

### Q: "Why Server Actions over a traditional API route? Seems like same thing with less code."

> **Less code 係其中一個 bonus，但 core value 係 type safety + progressive enhancement。**
>
> Traditional route：client 寫 `fetch('/api/cart', { method: 'POST', body: JSON.stringify(...) })`，server 寫 `await req.json()`，兩邊 types 手動同步（或透過 OpenAPI codegen）。Server Action 做到嘅：**同一個 TypeScript import，types 自動 flow**。
>
> 仲有 **progressive enhancement** — `<form action={serverAction}>` 喺 JS disabled 都 work（native form POST），React hydrate 之後升級做 client-side navigation + streaming。API route 做唔到呢件事 —— client 冇 JS 就 fetch 不了。
>
> **Trade-off：** Server Actions 係 **RSC-specific** —— 只有 Next.js (RSC) 用得到。如果你 app 要俾 mobile app / `curl` / non-Next client call，write traditional REST endpoint。實戰 pattern 係 **兩者並存**：web 用 Server Actions，public API 用 REST。

### Q: "What happens if my Server Action throws? Does the optimistic state really rollback automatically?"

> **React 幫你 auto-rollback optimistic state。** 流程：
>
> 1. Client call action → action 開始跑
> 2. `applyOptimistic(...)` → React render 用 optimistic reducer 計嘅新 state
> 3. Action throw → React 喺 next render cycle **discard** optimistic state，revert `useOptimistic`'s base 返 real state
> 4. Error 仍然會 bubble 上嚟，你可以用 `try/catch` 或 React error boundary catch
>
> **但：** rollback 只還原 **`useOptimistic` state**。其他 side effect（toast notification、URL change、analytics event）要自己 handle。
>
> **Debug tips：**
> - 加 `throw new Error("test")` 喺 action 頂測 rollback flow
> - Slow throttle + throw → 先見 optimistic apply → 再見 bounce back，確認 rollback timing

### Q: "When should you NOT use Server Actions?"

> **4 個場景：**
>
> 1. **多 client 要 call 同一個 endpoint** — mobile app、Android、iOS、third-party integration。Server Action RSC-specific，唔通用。用 REST。
> 2. **Long-running operation** — Server Actions 行喺 request cycle 入面，預期幾秒內完。要 > 10 秒就 offload 去 job queue（e.g. BullMQ + worker process）。
> 3. **Streaming response（SSE、WebSocket）** — Server Actions 係 request/response，唔支持 server-initiated push。要用 WebSocket / Server-Sent Events / Next 嘅 `streamResponse` pattern。
> 4. **Read-heavy endpoint（GET 類）** — Server Actions 暗地裡係 POST，唔啱做 cacheable GET。Read 用 server component `fetch()` 或 Route Handler（`/api/.../route.ts`）。
>
> **原則：** Server Actions 係 **state-mutating** 操作嘅 sweet spot（form submit、button click-to-save）。純讀、streaming、cross-client API 各有更啱工具。

---

## Practical deliverable

L28 分 8 個 commit（按時序）：

| Commit | 內容 |
|---|---|
| `feat(web): L28 Part 1 - cart page read-only from cookie` | `lib/cart/types.ts` + `lib/cart/store.ts` + `app/cart/page.tsx` empty + populated state |
| `feat(web): L28 Part 2 - addToCart server action` | `lib/cart/actions.ts` with `"use server"` + `AddToCartButton` wired up |
| `feat(web): L28 Part 3 - removeFromCart with form action` | `<form action={removeFromCart.bind(null, id)}>` per item + Remove button |
| `feat(web): L28 Part 4 - updateQuantity with FormData` | Qty `<form>` with number input + FormData parsing + 0 delegates to remove |
| `feat(web): L28 exercise 1 - clear cart action` | 🥉 `clearCart` + footer form |
| `feat(web): L28 exercise 2 - cart badge shows real count` | 🥈 Header async + `reduce` + conditional render |
| `feat(web): L28 exercise 3 - optimistic UI for remove with useOptimistic` | 🥇 Extract `CartItemList` client component + `useOptimistic` reducer |
| `docs: add lesson 28 cart server actions notes` | 本篇板書 |

**Verify：**

```bash
# Backend 要開
mvn spring-boot:run &

cd web
npm run dev

# 測試路線：
#   http://localhost:3000/products/1  → Add to cart
#   Header 🛒 badge 變 1
#   再 add 2 件 → badge 變 3
#   http://localhost:3000/cart        → 見 items list + Qty input + Remove + Subtotal + Clear Cart
#   Update qty 5                      → qty + subtotal 更新，badge 變 5
#   Update qty 0                      → item removed (delegate to removeFromCart)
#   Remove item                       → optimistic 即時消失
#   Clear Cart                        → empty state + Header badge 消失

# Type check
npm run typecheck        # → 0 errors

# Test optimistic (DevTools Network → Slow 4G)
#   Click Remove → 即時消失 + request pending ~2s + subtotal 延遲更新
```

---

## Next lesson

**L29 (tentative)：Auth flow + Cart migration**

- JWT login flow — backend L4 嘅 `/api/auth/login` endpoint 接入 frontend
- `httpOnly` cookie token storage（security best practice）
- `<AuthButton />` 由 stub 變 real（消除 `useState(false)` 嘅 sign-in simulation）
- **Cart migration** — login 時攞 cookie guest cart + backend user cart，merge 然後寫去 backend；logout 清 backend session 但 keep local guest cart
- Middleware — protected route pattern（`/orders`、`/account`）
- Route Handler 接 Server Action — auth state + cart mutation 一齊 touch DB
