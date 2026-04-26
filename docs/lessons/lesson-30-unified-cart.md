# Lesson 30: Unified Cart (Session-Aware) + Token Expiration Handling

**Date:** 2026-04-25 ~ 2026-04-26

L29 完成 auth flow，但留低咗一個 architectural gap —— `getCart()` 同所有 mutation 仲只係讀寫 cookie，logged-in user 嘅 backend cart 同 cookie 永遠**唔同步顯示**。L30 將 cart 整個 read / write 路徑改成 **session-aware**：有 token 行 backend，冇 token 行 cookie。順手起 role-based protected page、`/admin`，再做 token expiration handling 嘅 production-grade 防禦。

完整功能：unified `getCart()`、4 個 mutation actions session-aware（`addToCart` / `removeFromCart` / `updateQuantity` / `clearCart`）、`useOptimistic` 跨兩條路 verify、`revalidatePath` layout 模式、`/admin` page role-gated、`authFetch` helper + `TokenExpiredError` 處理 token expire。

⚠️ **Next.js 16 reminder** —— Server Component 唔可以寫 cookie（`cookies().set/delete()` 只可以喺 Server Action / Route Handler）。呢個 constraint 喺 Token expiration design 上係決定性嘅 factor。

---

## 核心概念

### 1. Session-aware `getCart()` —— 兩條路一個 facade

L28 嘅 `getCart()` 只讀 cookie。L30 改成：有 valid token 就走 backend，冇就走 cookie。

```ts
// web/lib/cart/store.ts
export async function getCart(): Promise<Cart> {
  const store = await cookies();
  const token = store.get(AUTH_COOKIE)?.value;

  if (token) {
    try {
      return await getBackendCart(token);          // ← 注意 await（後面詳述）
    } catch (err) {
      if (err instanceof TokenExpiredError) {
        return getGuestCart(store.get(CART_COOKIE)?.value);
      }
      throw err;
    }
  }
  return getGuestCart(store.get(CART_COOKIE)?.value);
}
```

**Public signature 唔變** —— `getCart(): Promise<Cart>`。所有 caller（`Header.tsx`、`/cart/page.tsx`）零改動，自動受惠 unified behavior。呢個係 **facade pattern** 嘅威力。

`getBackendCart` 同 `getGuestCart` 都係 internal helper，由 `getCart` 統一分流。

---

### 2. Backend helper —— `fetch` + Bearer + `cache: "no-store"`

```ts
async function getBackendCart(token: string): Promise<Cart> {
  const res = await fetch(`${API_BASE_URL}/api/cart`, {
    headers: { Authorization: `Bearer ${token}` },
    cache: "no-store",
  });
  if (!res.ok) return EMPTY_CART;
  const data = await res.json() as { items?: { productId: number; quantity: number }[] };
  return { items: (data.items ?? []).map(i => ({ productId: i.productId, quantity: i.quantity })) };
}
```

**3 個 must-have**：
- `Authorization: Bearer ${token}` —— RFC 6750 standard
- `cache: "no-store"` —— Cart 係 mutable user state，唔可以 cache
- `!res.ok` fallback —— 401/500 都 return EMPTY_CART（後面用 `authFetch` 升級）

**Backend 嘅 `CartResponse` shape** 比 frontend 用嘅 `Cart` type 富 —— 已經包含 product details。但係**為咗 minimum disruption**，L30 保持 frontend `Cart` type 唔變，將 backend 嘅 enriched data **map 落 minimal shape**。將來如果要省 round-trip，可以再 enrich frontend type。

---

### 3. Mutation Actions —— Session-aware 薄殼 pattern

```ts
export async function addToCart(productId: number, quantity: number) {
  const token = await getAuthToken();
  try {
    if (token) {
      await addToBackendCart(token, productId, quantity);
    } else {
      await addToGuestCart(productId, quantity);
    }
  } catch (err) {
    if (err instanceof TokenExpiredError) {
      const store = await cookies();
      store.delete(AUTH_COOKIE);
      redirect("/login?next=/cart");
    }
    throw err;
  }
  revalidatePath("/cart");
  revalidatePath("/");
}
```

**4 個 action 都係呢個結構**：
1. 攞 token
2. Try：分流 backend / guest
3. Catch `TokenExpiredError`：清 cookie → redirect login
4. `revalidatePath` 觸發 re-render

**Backend endpoints**：
| Action | Method | URL | Body / Param |
|---|---|---|---|
| add | `POST` | `/api/cart` | body `{productId, quantity}` |
| update | `PUT` | `/api/cart/{productId}?quantity=N` | **query param**，唔係 body |
| remove | `DELETE` | `/api/cart/{productId}` | — |
| clear | `DELETE` | `/api/cart` | — |

⚠️ **`updateQuantity` quirk** —— Spring Boot `@RequestParam int quantity` 等 query string，唔係 body。所以 frontend 要砌 `?quantity=N`，**唔需要 `Content-Type` header**。

---

### 4. Server-to-server fetch 喺 Network tab **隱形**

呢個係 Server Action mental model 嘅關鍵 —— Hibernate SQL log 見到 backend hit 咗，但 Browser DevTools Network tab **完全冇** `POST /api/cart`。

```
[Browser]                          ← Network tab 只見呢層
   │ Form submit (RSC payload)
   ▼
[Next.js server]                   ← Server Action `addToCart` 喺呢度行
   │ fetch /api/cart (Bearer)      ← 呢個 fetch 完全隱形
   ▼
[Spring backend]                   ← Hibernate SQL log 喺呢度先見證據
```

**驗證方法**：
- Browser DevTools Network → 篩 "Fetch/XHR" → 見到去 Next.js 嘅 form submit (Type = document/fetch)，header 有 `Next-Action: <hash>`
- Spring access log / Hibernate SQL → 證明 backend 真係收到
- 兩邊一齊睇先 trace 到完整 path

**教訓** —— Server Action debug 唔可以淨睇 Browser tab，要 Spring side 開 log。

---

### 5. `revalidatePath` —— `"layout"` mode + Router Cache invalidation

身份切換（login / logout / register）一定要用 `"layout"` 模式：

```ts
// web/lib/auth/actions.ts
import { revalidatePath } from "next/cache";

export async function logout() {
  const store = await cookies();
  store.delete("auth_token");
  revalidatePath("/", "layout");      // ← layout 模式
  redirect("/");
}
```

**`"page"` vs `"layout"`**：

| 模式 | 影響範圍 |
|---|---|
| `"page"`（default） | 只 invalidate 該 route segment 嘅 page.tsx |
| `"layout"` | 該 path 整條 layout chain (root + nested layouts + page) 都 invalidate |

Header 喺 `app/layout.tsx`，用 `"page"` 模式 invalidate `/` page **唔會 refresh Header**。所以 cart badge 唔變。`"layout"` 先正確。

**Router Cache invalidation** —— Next.js client side 有個 in-memory cache 收埋 visit 過嘅 server component output，目的：back/forward navigation 即時。但身份切換後，呢個 cache 變成 **安全洞**：

```
user A login → /account 入咗 router cache
user A logout → 按瀏覽器 back button
              → router cache serve 舊 snapshot
              → Header 仲 show user A username
              → /account body 仲 show user A 內容 💥
```

`revalidatePath(path, "layout")` 會 invalidate **server-side cache + client router cache**，逼 client 下次 navigate 重攞 fresh render。

**呢個就係：Router Cache 係為咗快，但身份切換後變成安全洞，`revalidatePath` 係解藥。**

---

### 6. Role-based protected page —— Page-level vs Proxy

`/admin` page：

```tsx
// web/app/admin/page.tsx
export const dynamic = "force-dynamic";

export default async function AdminPage() {
  const session = await getSession();
  if (!session) redirect("/login?next=/admin");
  if (session.role !== "ADMIN") {
    return <div><h1>403 — Access Denied</h1>...</div>;
  }
  return <main>...Admin Dashboard...</main>;
}
```

**Defence in depth** —— Proxy + Page 一齊用：

| Layer | 角色 | 做咩 |
|---|---|---|
| **Proxy** (`/admin/:path*`) | Edge runtime fast filter | 冇 token 即時 redirect login，唔 render page，慳 backend resource |
| **Page** | Node runtime authoritative check | Decode JWT、check role、render 403 / dashboard |

**為何 page-level 唔淨係靠 proxy？**
- Proxy edge runtime 只 check「**有冇 token**」，唔 verify expiration / role（要做都得，但增加 edge latency）
- Page 已經 decode 過 session，加 role check 零成本，仲可以 render 靚 403 UI
- 防禦 token 過期、proxy bypass、edge runtime bug

**TypeScript bonus**：`redirect()` return type 係 `never`，所以 `if (!session) redirect(...)` 之後，TS narrow `session` type 由 `Session | null` 變 `Session`，後面 `session.role` 唔需要 `!`。

**UserMenu admin link**：`{session.role === "ADMIN" && <Link href="/admin">Admin</Link>}` —— UserMenu refactor 由收 `username` prop 變收 whole `session` object，將來加 user-aware feature 唔需要再加 props drilling。

---

### 7. Token Expiration —— `authFetch` helper + `TokenExpiredError`

JWT 有 `exp`，會過期。L30 用 centralized fetch helper + 自定 error class 統一處理。

```ts
// web/lib/api/authFetch.ts
export class TokenExpiredError extends Error {
  constructor() {
    super("Token expired");
    this.name = "TokenExpiredError";
  }
}

export async function authFetch(token: string, url: string, init?: RequestInit) {
  const res = await fetch(url, {
    ...init,
    headers: { ...init?.headers, Authorization: `Bearer ${token}` },
    cache: "no-store",
  });
  if (res.status === 401) throw new TokenExpiredError();
  return res;
}
```

**呢個 helper 嘅 3 個設計決定**：
1. **Pure** —— 唔掂 cookie。Caller 知道自己 context（Server Action 定 Server Component），由 caller 決定點 cleanup
2. **只 catch 401** —— 其他 HTTP error 留俾 caller 自己 throw / handle
3. **Custom Error class** —— Caller 用 `instanceof TokenExpiredError` 區分，唔需要 string match

**對應 caller 處理**：

| Caller | Context | 點處理 401 |
|---|---|---|
| Mutation actions | Server Action | Catch → delete cookie → redirect login |
| `getCart` (via `getBackendCart`) | Server Component | Catch → silent fall back to guest cart（**唔可以 delete cookie**）|

---

### 8. Cookie write —— Server Action / Route Handler **only**

```
                 ┌─────────────────────────┐
                 │  cookies().set/delete() │
                 └────────────┬────────────┘
                              │
              ┌───────────────┴────────────────┐
              │                                │
         ✅ 可以叫                          ❌ 唔可以叫
              │                                │
    ┌─────────┴────────┐              ┌────────┴─────────┐
    │ Server Actions   │              │ Server Components│
    │ Route Handlers   │              │ Layouts          │
    └──────────────────┘              │ generateMetadata │
                                       └──────────────────┘
```

**點解咁設計** —— Server Component render 階段，HTTP response headers 已經開始 stream 出去，理論上唔再應該改 `Set-Cookie`。Server Action 反而係 request handling 階段，仲有時間改 response headers。

**踩坑 reproduction**：
```ts
// ❌ authFetch 早期版本
if (res.status === 401) {
  const store = await cookies();
  store.delete("auth_token");        // 💥 喺 Server Component context throw
  throw new TokenExpiredError();
}

// 觸發 path：refresh /cart → page.tsx → getCart → getBackendCart → authFetch → 401 → store.delete → BOOM
// Error: Cookies can only be modified in a Server Action or Route Handler.
```

**Fix** —— `authFetch` 變 pure，cookie cleanup 推去 caller：
- Server Action caller：可以 delete
- Server Component caller：唔可以 delete，accept bad cookie 留低（下次 mutation 會清）

---

### 9. Floating Promise —— Try/catch 唔 await 等於零

```ts
// ❌ 老師親身踩過
try {
  if (token) {
    return getBackendCart(token);    // ← 冇 await
  }
} catch (err) {
  if (err instanceof TokenExpiredError) { ... }
}
```

**點解 catch 接唔到**：
1. `getBackendCart(token)` 即場 return Promise（仲未 throw）
2. `return <Promise>` —— `getCart` 將 pending Promise 丟出去
3. **try block 即場結束** —— 冇 throw，catch skip
4. 過咗一陣，Promise reject (throw `TokenExpiredError`)
5. **已經出咗 try 範圍** → uncaught 💥

**Fix** —— 加 `await`：
```ts
return await getBackendCart(token);     // ← await 令 reject 喺 try 內 throw
```

**規則**：
> 「**如果你想 catch 一個 async function 嘅 error，必須 await 佢**」

呢個 trap 喺 JS 世界叫 **"floating promise"**。Lint rule `no-floating-promises` 就係防呢個 —— codebase 暫時冇開，靠人眼 + experience。

L30 入面 `clearCart` 第一版同 `getCart` try/catch 都犯過同一個錯。**重學兩次嘅同一個課**。

---

### 10. Frontend Display State ≠ Source of Truth

改壞 token 之後 refresh `/cart`：
- ✅ Cart 變空（backend 401 → silent fallback 行 guest 路）
- ❌ UserMenu 仲 show `testuser2` username

**呢個係 trust boundary 嘅實戰示範**：

```
┌──────────────────────┐    ┌──────────────────────┐
│ Cart                 │    │ UserMenu             │
│ ↓ getCart()          │    │ ↓ AuthButton         │
│   getBackendCart()   │    │   getSession()       │
│     fetch /api/cart  │    │     decodeJwtPayload │
│       backend → 401  │    │       (本地 base64)  │
│   silent fallback ✅ │    │   仲 return user ❌  │
└──────────────────────┘    └──────────────────────┘
       Truth = Backend            Trust = Frontend decode
```

**為何 frontend 唔自己驗 signature？**
1. Signature secret 唔可以畀 client 知（一旦泄漏 hacker 可以 forge）
2. Server 永遠係 source of truth，frontend decode 只係**為咗顯示方便**

**實戰決定** —— **接受呢個 trade-off**：
- UserMenu 仍 show user → user 撳任何 mutation → backend 401 → catch → 清 cookie → redirect login → consistent
- 如果要 100% 一致，可以用 React `cache()` + `/api/auth/validate` endpoint，但每 page render 多打一次 backend，cost 唔抵
- 大部分 production app 都接受呢個 transient inconsistency

---

## 實戰反思

### 反思 1 —— 同一個 `await` 課重學兩次

L30 入面，`clearCart` 出咗 await bug，後尾 `getCart` 又出咗同一個 bug。第二次出嗰陣 console 咁講：

```
Uncaught TokenExpiredError: Token expired
    at authFetch
    at getBackendCart
    at Header
```

第一次學完 `clearCart` await 之後，覺得 "識咗"。但係換咗 try/catch context 又再踩。**規則記住容易，real-world reflexes 要 多撞先 wire 入肌肉記憶**。

**Take-away** —— 任何 async function call **default 都應該諗下要唔要 await**。特別是：
- 嗰個 function 會 throw？（你要唔要 catch？）
- 嗰個 function 嘅 side effect 後面 logic 依賴？（race condition）
- 你係咪 return 緊佢？（不 await return Promise，await 之後 return value）

**最終預防** —— 開 ESLint `@typescript-eslint/no-floating-promises` rule。呢個係 future task。

---

### 反思 2 —— Server-to-server fetch 隱形帶嚟嘅 debug 困惑

第一次試 `addToBackendCart` 嘅時候，Browser DevTools Network tab 完全見唔到 `POST /api/cart`，誤以為 fetch 冇行。要 Spring 後台 Hibernate SQL log 先確認 backend 真係收到。

呢個係 Server Action mental model 嘅關鍵 —— **fetch 喺 Next.js server process 入面行，瀏覽器完全唔知道**。瀏覽器只見到自己 form submit 去 Next.js（RSC payload），server 內部嘅 outbound HTTP call 隱形。

**Take-away** —— Debug Server Action 一定要兩邊 log 一齊睇：
- Browser tab：confirm form submit 有發出（唔係 client side bug）
- Spring log：confirm backend 收到（confirm Server Action 真係 call）
- Hibernate SQL：confirm DB 有反應（confirm business logic 行咗）

三層任何一層斷，root cause location 就 narrow 咗。

---

### 反思 3 —— Backend endpoint URL 老師記錯

老師最初畀嘅 Part 2 spec 寫咗 `/api/cart/items`，實際 backend `CartController` 全部 endpoint 係 `/api/cart` 同 `/api/cart/{productId}`。靠睇 `CartController.java` 先 caught error。

**Take-away** —— 寫 frontend integration spec **永遠要 ground truth from backend code**，唔可以靠記憶或 convention 估。Spring 嘅 `@RequestMapping` + `@PostMapping` 係 single source of truth。

呢個 reflexion 嘅延伸 —— L30 仲撞到 `updateQuantity` 用 `@RequestParam` 而唔係 `@RequestBody`。所以 PUT 要傳 query string `?quantity=N`，唔係 body。**Spring annotation 一個字嘅差別決定 frontend 要點寫**。

---

### 反思 4 —— 接受唔完美嘅 UX consistency

UserMenu 仲 show username 但 cart 變空 —— 呢個係 frontend 信任本地 JWT decode 嘅必然 trade-off。完美 fix 要：
- 額外 backend endpoint (`/api/auth/validate`)
- React `cache()` per-request memoization
- 每 page render 多一次 backend call

**Cost-benefit 唔抵**。Production app 嘅取捨係：
- Display 用 cheap 本地 decode（best-effort，可能 stale）
- 任何 mutation / sensitive operation backend authoritative check

User 撞到 inconsistency 嘅機會：
- Token 自然 expire（罕見，因為 refresh token / re-login）
- Backend 主動 revoke token（罕見，admin panel）
- Token 被 hacker tamper（攻擊場景，本來就要 redirect login）

**接受 transient inconsistency 換取 99% case 嘅 performance 同簡單性**。呢個係 architectural pragmatism 嘅 example。

---

## 實戰深入問題

### Q: "Why silent fallback for read but redirect for write?"

> **失敗嘅 reversibility 同 user 期望唔同。**
>
> **Read 失敗** = recoverable。User 可以 refresh、navigate 開、過陣再 try。最壞 case 只係**少咗野睇**。Cart 變 empty 嘅體驗比 cart 整個 crash 好太多。
>
> **Write 失敗 (silent)** = **silent data loss**。User 以為加咗產品入 cart，但 mutation 飄走咗冇 sync 落 backend。下次去 cart 見到「Wait 我點解唔見?」👻。Write 必須**強制 user 睇到失敗**。
>
> 仲有：
> - **Read** 對 guest / logged-out user 都有意義（睇 guest cart 都 OK）
> - **Write** 必須有明確 user identity（「加產品入邊個 user 嘅 cart？」冇 auth 就冇答案）
>
> 所以：read fail → silent fallback；write fail → redirect 強制 re-establish session。

### Q: "Pre-flight token check (本地 verify exp before fetch) 嘅好處同壞處？"

> **本地 pre-flight = best-effort optimization，唔係 security boundary。**
>
> **好處**：
> - **省 latency** —— 本地 check 微秒級，唔需要 wait backend round-trip (10–500ms)
> - **省 backend load** —— Expired token 嘅 user spam refresh，每次都打 backend 浪費資源
> - **更快 redirect** —— 本地即場 detect 即場 reject
>
> **壞處**：
> 1. **Signature 唔驗** —— Frontend 冇 secret key，所以 hacker 可以寫 fake token，payload `{"sub":"admin","role":"ADMIN","exp":9999999999}`。Frontend pre-flight 過 ✅，但 backend 仍 reject 401。Frontend 顯示 admin UI lie 緊 —— 呢個就係 trust boundary 嘅核心 risk
> 2. **Clock skew** —— User device 時鐘行錯（時區設錯、開咗幾日機冇 sync），`Date.now()` 同 `exp` 比較會錯：
>    - 慢 clock → false negative（frontend 覺得仲 valid，backend reject）
>    - 快 clock → false positive（frontend 即場 reject，user 體驗：「我頭先先 login wor」）
>
> **正確 mental model** —— 本地 pre-flight 係 **performance hint**，**唔係 authoritative gate**。Backend response 永遠係 truth。

### Q: "Why can server components only read cookies, not write?"

> **Response lifecycle 嘅 constraint。**
>
> Server Component render 嘅階段，HTTP response headers 已經開始 stream 出去（包括 body chunk）。一旦 stream 開始，`Set-Cookie` 就唔再可以加。所以 React 19 + Next.js 14+ 嘅設計係 **Server Component cookies() 只 expose read methods**。
>
> Server Action 不同 —— 佢喺 client 觸發 form submit / explicit RPC call 嘅階段行，Next.js 仲未開始 stream response 落 client。所以可以：
> - Modify cookies (`set/delete`)
> - Call `redirect()` (issue 303 + Location header)
> - 一切 response-side mutation
>
> Route Handler (`app/.../route.ts`) 同 Server Action 一樣係 explicit handler，可以 mutate response。
>
> **Workaround pattern** —— 如果 Server Component 真係要清 cookie（例如 detect token corrupt），有兩個 work-around：
> 1. **Tolerate bad cookie** —— Accept 留低，下次 mutation 會清。L30 用呢個。
> 2. **Trigger client-side redirect to a Route Handler** —— Render 一個 client component 用 `useEffect` POST 去 `/api/auth/clear-bad-token`。複雜，少用。
>
> 接受 (1) 嘅 pragmatism 通常贏。

---

## Practical Deliverable

L30 分 7 個 commit：

| Commit | 內容 |
|---|---|
| `feat(web): L30 Part 1 - unified getCart with backend/cookie branching` | `lib/cart/store.ts` session-aware read path |
| `feat(web): L30 Part 2 - mutation actions session-aware (add/remove/update/clear)` | `lib/cart/actions.ts` 4 個薄殼 + 8 個 helper |
| `feat(web): L30 Part 3 - verify useOptimistic across both routes + L29 badge bug auto-fix` | 純驗證，零 code 改動（confirm Part 1 自動修咗 L29 gap） |
| `feat(web): L30 exercise 1 - revalidatePath layout mode in login/logout/register` | `lib/auth/actions.ts` 三處加 `revalidatePath("/", "layout")` |
| `feat(web): L30 exercise 2 - role-gated /admin page + UserMenu admin link` | `app/admin/page.tsx` + `_components/UserMenu.tsx` + `proxy.ts` matcher |
| `feat(web): L30 exercise 3 - token expiration with authFetch + TokenExpiredError` | `lib/api/authFetch.ts` + `lib/cart/store.ts` catch + `lib/cart/actions.ts` catch |
| `docs: add lesson 30 unified cart notes` | 本篇板書 |

**Verify：**

```bash
# Backend 要開
mvn spring-boot:run &

cd web
npm run dev

# ===== Part 1: Read path session-aware =====
# Logout (清 auth_token)
# Add 2 items 入 guest cart → Header badge = 2
# /cart 見 cookie 路 cart
# Login → migrate → backend cart
# Header badge 仍 = 2 (而家走 backend，L29 gap 自動 fixed)
# /cart 見 backend cart

# ===== Part 2: Mutation session-aware =====
# Login state: 加產品 → Hibernate UPDATE log (accumulate 已 existing item)
# Login state: Remove → Hibernate DELETE log
# Login state: Update quantity 5 → Hibernate UPDATE
# Login state: Clear → Hibernate DELETE WHERE user_id (cascade)
# Logout state: 全部走 cookie，DevTools Application → Cookies 見變化

# ===== Exercise 1: Layout-mode revalidation =====
# 兩個 tab 都 login → 喺 tab A logout → 去 tab B refresh
# Tab B Header 已 reflect logout（badge 變 0、UserMenu 變 Sign in）
# 反之亦然

# ===== Exercise 2: Role-based protection =====
# Update 一個 user role → ADMIN (SQL: UPDATE users SET role='ADMIN' WHERE username=...)
# Login normal user → /admin → 403 page
# Login admin user → /admin → Admin Dashboard
# Admin user UserMenu → 見 "Admin" link
# Normal user UserMenu → 冇 "Admin" link
# Logged out → /admin → proxy redirect /login?next=/admin

# ===== Exercise 3: Token expiration =====
# Login → 改壞 auth_token cookie value (DevTools Application → Cookies)
# Refresh /cart → 見 guest cart (silent fallback)，無 crash
# 加產品 → redirect /login?next=/cart，cookie 已清
# Login 完 → 返 /cart，consistent
```

**File 改動 summary：**

```
web/lib/api/authFetch.ts           NEW    pure fetch helper + TokenExpiredError
web/lib/cart/store.ts              MOD    getCart session-aware + try/catch
web/lib/cart/actions.ts            MOD    4 個薄殼 + 8 個 helper + try/catch
web/lib/auth/actions.ts            MOD    revalidatePath("/", "layout") x3
web/app/admin/page.tsx             NEW    role-gated dashboard
web/app/_components/UserMenu.tsx   MOD    收 whole session + admin link
web/app/_components/AuthButton.tsx MOD    pass session 落 UserMenu
web/proxy.ts                       MOD    加 /admin/:path* matcher
docs/lessons/lesson-30-unified-cart.md  NEW
```

**Bug timeline**（誠實記錄）：

1. ❌ `addToBackendCart` 第一版 copy 咗 cookie merge logic（誤以為 helper 內裡要做 merge）→ 改成純 fetch
2. ❌ `addToGuestCart` `if (existing)` 但冇 `else`，即使 existing 都會 push → duplicate item bug
3. ❌ `clearCart` 漏咗 `await` → race condition (revalidate 早過 fetch finish)
4. ❌ `authFetch` 早期版本喺 `cookies().delete()` → Server Component context throw
5. ❌ `getCart` try/catch 入面冇 `await` → Floating Promise，catch 接唔到
6. ❌ Admin page `!session` case 應 redirect 但寫成 render UI（spec 偏離）
7. ❌ Admin page 標題 "Account" → 應該 "Admin Dashboard"（copy-paste 漏改）

**Known limitation**（intentional trade-off）：

- **UserMenu / Cart inconsistency on token tamper** —— 改壞 token 後 refresh，cart 變 empty 但 UserMenu 仲 show username。原因：frontend `getSession` 只本地 decode，唔驗 signature；backend 401 觸發 cart fallback，但 UserMenu 唔知。Trade-off accepted —— next mutation 會 trigger redirect login resolve inconsistency。完美 fix 需要 React `cache()` + `/api/auth/validate` endpoint，cost 唔抵。
