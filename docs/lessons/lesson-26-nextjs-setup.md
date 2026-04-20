# Lesson 26: Next.js App Router + BFF Consumption

**Date:** 2026-04-18

呢課係 **Phase 1 frontend 第一堂**。L25 backend 已經開咗 CORS、暴露咗 `/api/feed` 同 `/v3/api-docs`。今堂 scaffold Next.js 16 App Router，用 `openapi-typescript` 由 backend OpenAPI spec generate TS types，再寫第一個 **server component** 直接 `await fetch('/api/feed')` render homepage。

⚠️ **Next.js 16 唔係你熟嘅 Next.js** — cache 預設、`fetch` 行為、`async` page 全部同 13/14 版本唔同。寫 code 之前睇 `web/node_modules/next/dist/docs/` 同 `web/AGENTS.md`。

---

## 核心概念

### 1. React Server Components (RSC) — page 本身係 async function

**對比傳統 React：**

```tsx
// Client-side React: data fetch 喺 useEffect，render 2 次（loading → data）
function Home() {
  const [feed, setFeed] = useState(null);
  useEffect(() => { fetch('/api/feed').then(r => r.json()).then(setFeed); }, []);
  if (!feed) return <Spinner />;
  return <Feed data={feed} />;
}
```

```tsx
// Next.js 16 Server Component: code 喺 server 行，HTML stream 返 browser
export default async function Home() {
  const feed = await getFeed();          // 喺 Node.js runtime 行
  return <Feed data={feed} />;           // 直接 render 完整 HTML
}
```

**點解 server component 係 default**：

- **Zero JS by default** — 呢個 component 零 client bundle 成本。只有加 `'use client'` 嘅 tree 先會 hydrate。
- **直接 hit DB / internal API** — 唔洗 expose endpoint 俾 browser，唔洗驗 JWT（server-to-server trusted context）。
- **Secrets 唔會 leak** — 環境變數（例如 `DATABASE_URL`）唔會 bundle 落 browser。
- **SEO / LCP** — 第一畫 HTML 已經有 feed content，唔洗等 JS hydrate 先 fetch。

**Gotcha：** `async` function 只可以用喺 server component。加 `'use client'` 之後個 page 就唔可以再 `await` top-level — 要改返 `useEffect`。

---

### 2. BFF consumption — `fetch` 直接 call backend

```ts
// web/lib/api/client.ts
import type { components } from "./types";

type FeedResponse = components["schemas"]["FeedResponse"];

const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

async function apiGet<T>(path: string): Promise<T> {
  const res = await fetch(`${API_BASE_URL}${path}`, {
    headers: { Accept: "application/json" },
    cache: "no-store",
  });
  if (!res.ok) {
    throw new Error(`API ${path} failed: ${res.status} ${res.statusText}`);
  }
  return res.json() as Promise<T>;
}

export function getFeed(): Promise<FeedResponse> {
  return apiGet<FeedResponse>("/api/feed");
}
```

**重點：**

- `Accept: application/json` **必要** — backend 有 `jackson-dataformat-xml`，content negotiation 唔講清楚就可能返 XML（browser view-source 就係咁中招）。
- `process.env.NEXT_PUBLIC_API_BASE_URL` — `NEXT_PUBLIC_` prefix 先會 bundle 到 client；server component 其實 server env 都讀得到，但 future client component 都共用呢個 constant 所以加 prefix。
- `cache: "no-store"` — Next 16 行為同 Next 15 反轉（Next 15 預設 cache，Next 16 預設 no-store），但寫清楚更明白。
- Generic `apiGet<T>` — 下堂加 `getProduct(id)`、`getCategories()` 可以直接 reuse。

---

### 3. Next.js 16 Cache Components — `dynamic = "force-dynamic"` 點解要加

**問題：** `npm run build` fail：

```
Error: fetch failed
  cause: Error: connect ECONNREFUSED ::1:8080
```

**原因**：Next.js 16 build 時試圖 **prerender** `/` 做 static HTML。Build-time backend 未起，fetch 失敗。

**修：**

```tsx
// web/app/page.tsx
export const dynamic = "force-dynamic";

export default async function Home() { ... }
```

**四種 rendering mode：**

| Mode | 何時 render | 何時用 |
|---|---|---|
| Static (default) | Build time | Marketing page、blog post |
| ISR (`revalidate: N`) | Build + 每 N 秒背景 regenerate | Product list 變動唔頻密 |
| Dynamic SSR (`force-dynamic`) | 每個 request | Homepage feed、personalised |
| CSR (`'use client'` + `useEffect`) | Browser | 用戶 interaction driven |

Homepage feed 會跟 stock / price 變，選 **Dynamic SSR**。將來轉 `revalidate: 60` 做 ISR 換 perf。

---

### 4. OpenAPI codegen — single source of truth

```json
// web/package.json
"scripts": {
  "codegen": "openapi-typescript http://localhost:8080/v3/api-docs -o lib/api/types.ts",
  "typecheck": "tsc --noEmit"
}
```

**Flow：**

1. Backend `springdoc-openapi` 自動睇 `@RestController` + DTO 生成 `/v3/api-docs` JSON。
2. `npm run codegen` 讀呢個 JSON → `lib/api/types.ts`（auto-generated，唔好手 edit）。
3. TS import `components["schemas"]["FeedResponse"]` → compile-time type-safe。
4. Backend 改 `FeedResponse` 加 field → 重 run codegen → 舊 frontend code 即刻 red underline。

**Generated shape（簡化版）：**

```ts
export interface components {
  schemas: {
    FeedResponse: {
      featuredProducts?: components["schemas"]["ProductResponse"][];
      newArrivals?: components["schemas"]["ProductResponse"][];
      categories?: components["schemas"]["CategoryResponse"][];
    };
    ProductResponse: {
      id?: number;
      name?: string;
      price?: number;
      /* ... */
    };
    CategoryResponse: { id?: number; name?: string; };
  };
}
```

**全部 optional** 因為 Spring 無 `@Schema(requiredMode = REQUIRED)`。下一個概念處理。

---

### 5. Optional field pattern — 點樣食埋無 `required` 嘅 schema

**問題：** `tsc --noEmit` 報 `'feed.featuredProducts' is possibly 'undefined'`。

**四個選擇：**

| 方案 | 做法 | 取捨 |
|---|---|---|
| A. `??` 每次用都 fallback | `(feed.featuredProducts ?? []).map(...)` | 冗長，到處重複 |
| B. Destructure with defaults | `const { featuredProducts = [] } = feed;` | 一次過定 default，乾淨 |
| C. `!` non-null assertion | `feed.featuredProducts!.map(...)` | 危險，runtime crash 無警告 |
| D. Backend 加 `@Schema(requiredMode = REQUIRED)` | 修 source | 正統但要 round-trip，依賴 backend |

我哋揀 **B**：

```tsx
const feed = await getFeed();
const { featuredProducts = [], newArrivals = [], categories = [] } = feed;
```

**結構性 typing 陷阱：** `ProductResponse.id` 同 `CategoryResponse.id` 都係 `number`，`.name` 都係 `string`，所以 `categories.map(p => <li key={p.id}>{p.name}</li>)` 就算 copy-paste 錯咗變數名都**唔會 type error**。真係 debug 要靠眼睇 + UI 見到三個 section 全部一樣嘅 product list 先察覺。Lesson：**copy-paste render block 之後一定要逐個核 variable name**。

---

## Gotchas 踩過嘅坑

### Gotcha 1: `8080 already in use` 但 `netstat -ano` 空

**症狀：** `mvn spring-boot:run` 報 port bind fail，Windows netstat 睇唔到 process。

**原因：** Docker Desktop 用 WSL2 backend，container-forwarded port 唔會出現喺 Windows 嘅 TCP table。

**排查：**
```bash
docker ps                                       # 揾有無 container bind 8080
docker compose down                             # 清 stale container
# PowerShell:
Get-NetTCPConnection -LocalPort 8080
# WSL:
ss -tlnp | grep 8080
```

**最後解決：** Restart Docker Desktop。

---

### Gotcha 2: Browser view feed 係 XML 唔係 JSON

**症狀：** `view-source:http://localhost:8080/api/feed` 顯示 `<FeedResponse><featuredProducts>...`，`curl` 返 JSON。

**原因：** `pom.xml` 含 `jackson-dataformat-xml`（之前 pretty-print console log 加嘅），browser 自動 `Accept: application/xml,text/html;q=0.9,*/*;q=0.8` → Spring content negotiation 揀 XML。

**我哋嘅處理：** `client.ts` 顯式送 `Accept: application/json`，production flow 唔受影響，browser 直 view 嘅 XML 唔理。

**徹底修法（如果要）：** `@RestController` 加 `@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)`。

---

### Gotcha 3: `/v3/api-docs` → 500 `NoSuchMethodError`

**症狀：**
```
java.lang.NoSuchMethodError: 'void org.springframework.web.method.ControllerAdviceBean.<init>(java.lang.Object)'
    at org.springdoc.core.service.GenericResponseService.lambda$getGenericMapResponse$8
```

**原因：** Spring Boot 3.5 帶 Spring Framework 6.2，6.2 remove 咗 `ControllerAdviceBean(Object)` constructor。springdoc 2.6.0 仲 call 舊 signature。

**修：** `pom.xml` bump `springdoc-openapi-starter-webmvc-ui` `2.6.0 → 2.8.6`。

**Lesson：** 跨 Spring Boot minor version 升級時，springdoc / spring-cloud / any bytecode-level 擴展 library 要一齊升，binary-incompatible NoSuchMethodError 就係典型症狀。

---

## 面试答法

### Q: "Explain React Server Components. When would you NOT use one?"

> **RSC 係 server-side render 嘅 React component**，code run 喺 Node.js，output 係 HTML + React Server Payload，browser 攞到就直接畫，唔 hydrate（除非 tree 入邊有 client component）。
>
> **好處：** 零 JS bundle cost、直接 hit DB 唔洗 API round-trip、secrets 唔 leak、LCP 快。
>
> **幾時唔用：** 需要用戶 interaction 嘅 component（onClick、useState、useEffect）一定要 `'use client'`。例如 cart button、search-as-you-type、modal open/close。
>
> **常見設計：** Page-level 係 server component fetch data，入面揀啲細嘅 client component 處理 interaction — RSC 傳 serializable props 落去 client island。

### Q: "Why do you use a BFF endpoint from Next.js server component instead of calling backend directly from the browser?"

> **兩層 BFF：**
>
> 1. **Spring Boot `/api/feed`** — aggregate DB queries，business logic owner（e.g., "featured = page 0"）。
> 2. **Next.js server component** — 近 browser 嘅 assembly layer，shape 返 UI 特定 props（將來可能 merge CMS content + feature flag + A/B test bucket）。
>
> **從 server component call backend 勝過 browser direct call：**
>
> - **Network locality** — production 放同一個 VPC／cluster，server-to-server latency 低過 browser-to-server。
> - **Cookie forwarding policy** — next 喺 server 控制 expose 邊個 cookie，browser 直連就無 layer 可以 scrub。
> - **Render streaming** — next 可以 `<Suspense>` 分段 stream HTML；browser 直 fetch 做唔到。
>
> **Trade-off：** multi-hop 增加 1 個 failure domain。如果 next 掛晒 但 backend 活住，用戶都 access 唔到。要用 health-check + graceful fallback 頁。

### Q: "Your Next.js build keeps timing out trying to fetch data. What's going on?"

> **九成係 Next 試 prerender 一個 runtime-dependent page 做 static HTML。**
>
> Next 16 App Router 預設會 static-optimise 任何無 dynamic API 嘅 page。如果 page 喺 build time `fetch` 一個 build 時未起嘅 service，就 ECONNREFUSED。
>
> **三個修法：**
>
> 1. `export const dynamic = "force-dynamic"` — 明確講呢 page 每 request render。
> 2. `export const revalidate = 60` — 做 ISR，第一次 build 攞 data，之後每 60 秒 regenerate。
> 3. Build pipeline 起 backend 再 build — 最慢最脆弱，一般避免。
>
> **同場加映** — `fetch()` 嘅 `cache` option 喺 Next 16 預設 `no-store`（Next 15 預設 `force-cache`），migrate 時好多人以為自己 code 慢咗其實係行為變咗。

### Q: "How do you keep frontend types in sync with backend DTOs?"

> **OpenAPI codegen pipeline：**
>
> 1. Backend `springdoc-openapi` 自動 derive spec 由 `@RestController` + Jackson annotations → `/v3/api-docs`。
> 2. Frontend `npm run codegen` 用 `openapi-typescript` 讀 spec → generate `lib/api/types.ts`。
> 3. `tsc --noEmit` 喺 CI run，backend schema drift 即刻 build fail。
>
> **Gotcha：** Spring 預設 field 全部 optional（generated type 有 `?`）。兩個選擇：
>
> - Backend 加 `@Schema(requiredMode = RequiredMode.REQUIRED)` — 正統，但要 touch backend。
> - Frontend destructure with defaults `const { x = [] } = feed;` — 快，但如果 backend 真係漏咗 field runtime 就 silent 變空 array。
>
> **仲有：** Structural typing 會食落 `ProductResponse` 同 `CategoryResponse` 意外互換（兩者都有 `id: number; name: string;`），呢個 codegen 救唔到，要靠 integration test 或者 runtime Zod validation。

---

## Practical deliverable

L26 分 2 個 commit：

| Commit | 內容 |
|---|---|
| `chore: add openapi-typescript for backend type generation` | `web/package.json` 加 devDep + `codegen` / `typecheck` scripts |
| `feat: add Next.js homepage consuming /api/feed via typed client` | `lib/api/client.ts` + `lib/api/types.ts` (generated) + `app/page.tsx` |

**Verify：**

```bash
# Backend 要開
mvn spring-boot:run &

# 生 types
cd web
npm run codegen
# → lib/api/types.ts 有 components.schemas.FeedResponse

# Type check
npm run typecheck
# → 0 errors

# Dev server
npm run dev
# → http://localhost:3000 顯示 Featured / New Arrivals / Categories 3 個 section

# Production build（confirm dynamic = force-dynamic 有效）
npm run build
# → Route (app)
#   ƒ /                                     (Dynamic)    ...
```

---

## Next lesson

**L27 (tentative)：** dynamic route `app/products/[id]/page.tsx` + root `layout.tsx` navigation + 第一個 `'use client'` component（cart button）。會 cover：

- `generateStaticParams` vs runtime dynamic route
- `<Suspense>` streaming + `loading.tsx`
- Server ↔ Client component boundary：serializable props only, no functions / Dates crossing
- `revalidatePath('/products/[id]')` — on-demand ISR invalidation
