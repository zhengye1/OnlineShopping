# Lesson 27: JSX Fundamentals + Components + Navigation

**Date:** 2026-04-21

L26 scaffold 咗 Next.js + 第一個 server component render homepage。今課深入 **JSX 語法**、**component composition**、同 **App Router 嘅 routing / layout 系統**。亦係第一次寫 **client component**（`'use client'`）handle 互動。

完整功能：root layout 加 Header / Footer、dynamic route `/products/[id]` 睇單件商品、shared `<ProductCard />` reuse 喺 home + `/products` list、client-side `<AuthButton />` 有 sign in / out toggle + dropdown。

⚠️ **Next.js 16 Reminder** — `params` 係 `Promise<{...}>`，要 `await params` 先攞到 `id`。寫 code 前睇 `web/AGENTS.md`。

---

## 核心概念

### 1. JSX 12 條必記文法

JSX 唔係 HTML，係 JavaScript 表達式。以下 12 條係今課實際撞過嘅「grammar traps」。

| # | Rule | 例子 |
|---|------|------|
| ① | `{ }` 入面係 JS expression；template literal 用 backtick | `` href={`/products/${product.id}`} `` |
| ② | `className` 唔係 `class`（`class` 係 JS keyword） | `<div className="p-4">` |
| ③ | Component 名**大寫開頭**；props 用 destructuring 接 | `function ProductCard({ product }: ...)` |
| ④ | `return` 只可以 return **一個** root element（用 `<>...</>` Fragment 包多個） | `return <>...<... /></>` |
| ⑤ | `.map()` list render **必須有 `key`**，而且要 stable unique | `{items.map(i => <Li key={i.id}>)}` |
| ⑥ | Event handler 用 **camelCase + function reference** | `onClick={() => setOpen(true)}` |
| ⑦ | Conditional render 3 招：`&&` / ternary / early return | `{open && <Menu />}` |
| ⑧ | Self-closing tag 一定要 `/` | `<img src=... />` 唔係 `<img>` |
| ⑨ | `style` 係 object，double brace `{{...}}`；property 用 camelCase | `style={{ fontSize: 14 }}` |
| ⑩ | `htmlFor` 代替 `for`（`for` 係 JS keyword） | `<label htmlFor="email">` |
| ⑪ | `children` 係 props 入面嘅 **slot**，layout 用 | `function Layout({ children }) { return <main>{children}</main> }` |
| ⑫ | 冇 `if/else` 喺 JSX 入面 — 用 ternary 或者 `&&` | `{isLoggedIn ? <User /> : <SignIn />}` |

**Gotcha — `{}` 既係 expression block 又係 literal brace：**

```tsx
// 喺 attribute 位：{ } = JS expression
<div className={cls}>                   // cls 係變量

// 喺 attribute 位仲想寫 object literal：double brace
<div style={{ color: "red" }}>          // 外 brace = expression，內 brace = object literal

// Children 位：{ } 入面係任何 expression
<h1>{`Hello ${name}`}</h1>              // template literal
```

---

### 2. Server Component vs Client Component

**預設係 server component**。加 `'use client'` directive 先會 opt-in client bundle。

**邊啲情況要 client？**

| 要 client | 原因 |
|-----------|------|
| `useState` / `useReducer` | Hooks 淨係喺 browser runtime 行 |
| `useEffect` | 一樣 |
| `onClick` / `onChange` 等 event handler | Event listener 要 attach 喺 DOM |
| Browser-only API（`localStorage`, `window`） | Server 冇呢啲 |

**邊啲最好 server？**

- Data fetching（`await fetch`）— secret 唔 leak、network locality 好
- Render static content —— zero JS to browser
- SEO-critical page — HTML 第一次 response 就有 full content

**Composition pattern — server fetch + pass data to client：**

```tsx
// Server component
async function ProductDetail({ id }) {
  const product = await getProduct(id);           // server-side fetch
  return <AddToCartButton productId={product.id} stock={product.stock} />;
  //                      ^^^^^^^^^ props 係 serializable primitives
}
```

```tsx
// Client component
"use client";
import { useState } from "react";

export function AddToCartButton({ productId, stock }) {
  const [qty, setQty] = useState(1);              // hook OK，因為喺 client
  return <button onClick={() => add(productId, qty)}>Add to Cart</button>;
}
```

**⚠️ Props crossing boundary 嘅限制：** 只可以 pass **serializable** data（number / string / plain object / array）。**Function / Date / class instance 過唔到** —— Next.js 要將 server component 嘅 output serialize 做 RSC payload 送去 browser。

---

### 3. Dynamic Routes — `app/products/[id]/page.tsx`

File-based routing：folder 名 `[id]` = dynamic segment。

```
app/
  products/
    page.tsx          → /products          (list page)
    [id]/
      page.tsx        → /products/:id      (detail page)
```

**Next.js 16 breaking change：** `params` 由 sync object 變 `Promise`。

```tsx
// ❌ Next 15 及之前
export default function Page({ params }: { params: { id: string } }) {
  const id = params.id;
}

// ✅ Next 16
export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;                    // 要 await
  const product = await getProduct(Number(id));
  return <ProductDetail product={product} />;
}
```

**點解變 Promise？** 為咗支持 partial prerender / streaming — Next 想可以「先 render shell，後 resolve params」。如果你嘅 page 唔使用 `id` 就可以 stream 咗 header 出嚟。

---

### 4. Private Folder Convention — `_components/`

**Folder 名 `_` 開頭 = 唔係 route**。App Router 會 skip 佢。

```
app/
  _components/        ← 唔會被當做 /components route
    Header.tsx
    Footer.tsx
    ProductCard.tsx
    AuthButton.tsx
  products/
    page.tsx
```

**點解唔放 `web/components/` top-level？**

- 同一個 app 內 co-locate → 搵 component 唔使跨 folder 跳
- `@/components` alias 都得，但 App Router 文化習慣用 `app/_components`
- `_` 明確講「呢個唔係 route」避免誤會

**對比：`(group)` 係 route group（分組但唔影響 URL），`[slug]` 係 dynamic segment。三種 convention 唔好撈亂。**

---

### 5. Root Layout — `children` Slot

`app/layout.tsx` 係最外層 wrapper，**所有 page 都會 render 喺 `{children}` 位置**。

```tsx
// app/layout.tsx
import Header from "./_components/Header";
import Footer from "./_components/Footer";

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body className="min-h-screen flex flex-col">
        <Header />
        <main className="flex-1">{children}</main>      {/* ← page 嗰度 render */}
        <Footer />
      </body>
    </html>
  );
}
```

**`children` 唔係 React 特殊語法 — 係 props 嘅 convention。** 任何 component 都可以接 `children`：

```tsx
function Card({ children }: { children: React.ReactNode }) {
  return <div className="border p-4">{children}</div>;
}

// 用：
<Card>
  <h1>Title</h1>                       // ← 呢兩行就係 children
  <p>Body</p>
</Card>
```

---

### 6. `next/link` — Client-Side Navigation + Prefetch

```tsx
import Link from "next/link";

<Link href={`/products/${id}`}>View</Link>
```

**點解唔用 `<a>`？**

- `<a href>` = full page reload，Next client bundle 要重新 hydrate
- `<Link>` = **client-side nav**，只 swap page component tree，maintain scroll / state
- `<Link>` 喺 viewport 入面會**自動 prefetch** 目標 route 嘅 chunk，click 嘅時候近乎 instant

**Gotcha：** 外部 URL（`https://...`）用返 `<a>`，`<Link>` 係 internal route 專用。

---

### 7. Tailwind 記憶法 — Category 思維框架

抄 class 之前**逐 class 問自己用嚟做乜**。分類記就唔會忘。

| Category | 例子 class | 作用 |
|----------|-----------|------|
| **Layout** | `flex` `grid` `block` `hidden` `relative` `absolute` `sticky` | 整體排位 |
| **Sizing** | `w-full` `h-14` `min-h-screen` `max-w-6xl` `aspect-square` | 闊高 |
| **Spacing** | `p-4` `px-6` `py-2` `m-2` `mx-auto` `gap-4` `mb-8` | Padding / margin / gap |
| **Typography** | `text-sm` `text-xl` `font-semibold` `text-gray-700` `line-clamp-1` | 字 |
| **Background** | `bg-white` `bg-gray-100` `bg-white/90` `backdrop-blur` | 底 |
| **Border** | `border` `border-t` `border-gray-200` `rounded-lg` `rounded-full` | 框邊 |
| **Effect** | `hover:bg-gray-100` `hover:shadow-md` `transition` `z-40` | 互動 |

**Scale system（要背）：** `1 = 4px`, `2 = 8px`, `3 = 12px`, `4 = 16px`, `6 = 24px`, `8 = 32px`, `12 = 48px`, `14 = 56px`... 即 `p-4` = 16px padding。

**IDE 幫手：** JetBrains Tailwind CSS plugin — hover 睇到 generated CSS、autocomplete class name。

---

## 實戰反思（今課撞過嘅坑）

### 反思 1 — 抄 className 要問「呢 class 幫我做乜」

🥉 Footer 第一稿將 Header 嘅 class 整條抄過嚟：

```tsx
// ❌ 抄 Header 出事
<footer className="sticky top-0 z-40 bg-white/90 backdrop-blur border-b ...">
```

結果 Footer 變咗 sticky 喺頁頂（因為 `sticky top-0`）、有底邊 line（`border-b` 應該係 `border-t`）。

✅ Footer 需要：`border-t`（上邊 line）、`bg-gray-50`（淡底）、`mt-16`（同上面 content 隔開）。**唔同嘅 component 有唔同嘅語意，冇 copy-paste 銀彈。**

### 反思 2 — 「感覺怪」通常係 scope 問題，唔係 style

🥈 AuthButton 第一稿淨係 dropdown items 有 style、外層 button 光脫脫。「感覺怪」唔係因為 class 不足，係因為**未完成既 visual hierarchy** — toggle button 同 dropdown container 都冇 border / padding / shadow。

教訓：UI 好似哪度唔啱，先 audit **有冇漏嘢**（component / content / state），**後至** debug class。

### 反思 3 — Rebase mechanics：`squash` 嘅 prerequisite

🥇 Rebase 搞 mess up — 個 todo 第一行係 `squash`：

```
squash 6ec18b0 ...       ← 冇上一個 commit 可以 meld 入去
squash 6af35f5 ...
pick   64360ea ...
```

`squash` = meld into **previous** commit。第一行冇「previous」，git 卡住。

**正確：**
```
pick   6ec18b0 ...       ← 第一個永遠係 pick（做 base）
squash 6af35f5 ...       ← 之後先可以 squash 入去
pick   64360ea ...
```

**Pro tip：** Want squash 但又唔想開 editor prompt combine message？用 `fixup` 代替 `squash` — keep 第一個 commit message，drop 被 fixup 嗰個嘅 message。

### 反思 4 — Commit message reword 要喺 rebase **當下**做

Squash 嗰陣冇 `reword` 第一行 → rebase 完 typo 仍然喺度 → 要再多一次 `git rebase -i` 先改到 message。

下次一齊搞埋：
```
reword 6ec18b0 ...       ← 第一行 reword，opens editor for new message
fixup  6af35f5 ...       ← fixup 入去，冇 editor prompt
pick   64360ea ...
```

---

## 實戰深入問題

### Q: "Why does Next.js split Server vs Client components? Couldn't we just render everything on the server?"

> **Server component 唔識 handle 互動。** `onClick` handler 係 function reference —— function 過唔到 RSC serialization boundary，所以 server 冇辦法送「點咗 button 會 call 呢個 function」呢個指令去 browser。
>
> 同樣：`useState` 要 React runtime reconciler 喺 browser 度行 — server 行一次就散 memory，冇狀態保留可言。
>
> **所以 split：** Server component 負責 **static render + data fetch**，client component 負責 **state + interaction**。Next 幫你畫界線（`'use client'` directive），但係你 design component tree 嘅時候要主動諗「呢個 component 係 static 定 interactive？」
>
> **Trade-off：** 多 client component = 多 JS bundle = browser 慢。原則係**將 `'use client'` 推去 leaf component**（e.g. `<AuthButton />` 係 leaf，`<Header />` 仍然 server），唔好全個 tree 變 client。

### Q: "What's the failure mode if I forget the `key` prop in a `.map()`?"

> **React 冇辦法精確追蹤 list item 嘅身份。** Re-render 嘅時候 React 用 index fallback，list 加 / 刪 item 會令 DOM node 錯位 reuse。
>
> **User-visible bug：**
>
> - `<input>` 嘅 value 跑去另一個 item
> - Animation 喺錯嘅 item 上重播
> - `useState` 喺 list item component 入面 preserve 錯個 state
>
> **Debug 特徵：** Console warning `Each child in a list should have a unique "key" prop`。忽略呢個 warning 係 React bug 最常見源頭之一。
>
> **Gotcha：** 唔好用 **array index** 做 key —— 除非個 list **永遠唔會 reorder / insert / delete**。用 stable ID（database id、UUID）最安全。

### Q: "How do I decide between extracting a component vs leaving code inline?"

> **三條 trigger：**
>
> 1. **Reuse 第 2 次** — 第一次 inline OK，第二次就 extract。今課 `<ProductCard />` 就係：home page feature 用、`/products` list page 又用。
> 2. **Props surface 超過 3 個 + 有 logic** — inline 講故 flow 會亂，extract 之後 component 有自己 contract。
> 3. **測試需要 isolate** — extract 先可以獨立 unit test，唔洗整條 page render。
>
> **Trade-off：** Extract 太早會 over-abstract（"speculative generality"）。寧願 2 個地方 duplicate，第 3 個地方出現先 extract — 3 次 occurrence 先足夠睇清楚真正嘅 abstraction boundary（"rule of three"）。

---

## Practical deliverable

L27 分 7 個 commit（按時序）：

| Commit | 內容 |
|---|---|
| `feat(web): L27 product card grid + Add to Cart skeleton` | `app/page.tsx` 3 section grid + `app/products/[id]/page.tsx` + `AddToCartButton.tsx` |
| `feat(web): L27 global layout with sticky nav header` | Root `layout.tsx` 加 `<Header />` + `<Footer />` placeholder |
| `feat(web): L27 exercise 1 - footer component` | 🥉 `_components/Footer.tsx` 3-column grid |
| `feat(web): L27 exercise 2 - auth button toggle with dropdown` | 🥈 `_components/AuthButton.tsx` client component + `useState` conditional render |
| `refactor(web): extract ProductCard to shared component` | 🥇 Step 1 — `_components/ProductCard.tsx` + `app/page.tsx` 用返 |
| `feat(web): add getProducts API client and products list page` | 🥇 Step 2+3 — `lib/api/client.ts` 加 `getProducts()` + `app/products/page.tsx` |
| `docs: add lesson 27 JSX + components + navigation notes` | 本篇板書 |

**Verify：**

```bash
# Backend 要開
mvn spring-boot:run &

cd web
npm run dev

# 測試路線：
#   http://localhost:3000          → Home（Featured / NewArrivals / Categories）
#   http://localhost:3000/products → All products grid
#   Click card → /products/{id}    → Detail page + quantity controls
#   Click "Sign in" in Header      → 變 "User ▾"
#   Click "User ▾"                 → Dropdown appears
#   Click "Sign out"               → 返 "Sign in"
#   Click 🛒 (cart)                → /cart（暫時 404，L28 做）

# Type check
npm run typecheck        # → 0 errors

# Production build
npm run build
# → Route (app)
#   ƒ /                            (Dynamic)
#   ƒ /products                    (Dynamic)
#   ƒ /products/[id]               (Dynamic)
```

---

## Next lesson

**L28 (tentative)：** Cart state + mutation via Server Actions。會 cover：

- Cart persistence — cookie-backed vs database-backed trade-off
- Server Actions (`"use server"`) — form submission 唔使寫 API route
- `revalidatePath` / `revalidateTag` — invalidate server-rendered pages after mutation
- Optimistic UI — `useOptimistic` hook，click 即時更新 UI，server 失敗再 rollback
- 接手今課個 `<AuthButton />` stub → real JWT auth flow（配合 backend L4 嘅 `/api/auth/login`）
