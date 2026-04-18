# Lesson 25: CORS + BFF Pattern + OpenAPI

**Date:** 2026-04-17

呢課係 **Phase 1 → frontend 橋樑課**。之前 24 課寫 backend，下一課開始 Next.js。今堂要做嘅係俾 browser 喺 `localhost:3000`（Next.js dev server）可以安全噉 call 我哋喺 `localhost:8080` 嘅 Spring Boot API，同時加 3 件嘢：**CORS**、**BFF aggregate endpoint**、**OpenAPI + Prometheus**。

---

## 核心概念

### 1. CORS — 點解 browser 搞咁多嘢？

**Same-Origin Policy**：browser 預設唔俾 JS 跨 origin fetch 另一個 server 嘅 response。`http://localhost:3000` call `http://localhost:8080` 已經係 cross-origin（port 唔同）。

**Preflight**：如果 request 含自訂 header（例如 `Authorization: Bearer ...`）或 non-simple method（PUT/DELETE/PATCH），browser 會先發 `OPTIONS` 問 server「你容許呢個 origin + method + headers 嗎？」。

**我哋嘅 `CorsConfig`：**

```java
@Configuration
public class CorsConfig {

    @Value("${app.cors.allowed-origins:http://localhost:3000}")
    private List<String> allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization","Content-Type","X-Requested-With",
                                         "X-Trace-Id","Cookie"));
        config.setExposedHeaders(List.of("X-Trace-Id"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
```

**幾個重點：**
- `setAllowCredentials(true)` → 可以帶 `Cookie` / `Authorization` → **origin 唔可以用 `*`**，一定要明文 list。
- `setExposedHeaders("X-Trace-Id")` → 我哋自家 trace header 要顯式講「browser JS 可以讀」，否則 JS 睇唔到。
- `setMaxAge(3600)` → preflight 結果 cache 1 hour，減少重複 `OPTIONS`。
- `/api/**` only → actuator、swagger-ui 自己一套，唔會放寬 CORS。

**連到 Spring Security：** `SecurityConfig.securityFilterChain()` 加 `http.cors(Customizer.withDefaults())` → Security filter chain 會揾 `CorsConfigurationSource` bean 嚟用。無呢行，preflight 會俾 security 拒絕。

---

### 2. BFF (Backend-for-Frontend)

**Problem**：homepage 需要 featured products + new arrivals + categories。Naïve 做法係 browser 行 3 個 fetch：

```
/api/products?sort=featured&page=0&size=8
/api/products?sort=new&page=1&size=8
/api/categories
```

→ 3 round-trips、mobile 連 5G 都要 300ms × 3 = ~900ms、每 component 要獨立 loading state、server-side render 要 waterfall。

**Solution**：server side 一個 **aggregate endpoint** 一次過 return 晒。

```java
@Service
public class FeedService {
    private static final int PAGE_SIZE = 8;

    public FeedResponse getFeed() {
        var featured = productService.getAllProducts(0, PAGE_SIZE, "createdAt", "desc");
        var newArrivals = productService.getAllProducts(1, PAGE_SIZE, "createdAt", "desc");
        var categories = categoryRepository.findAll().stream()
                .map(c -> new CategoryResponse(c.getId(), c.getName()))
                .toList();
        return new FeedResponse(featured.getContent(), newArrivals.getContent(), categories);
    }
}

@RestController
@RequestMapping("/api/feed")
public class FeedController {
    @GetMapping
    public FeedResponse getFeed() { return feedService.getFeed(); }
}
```

**Trade-off：**
- Pro: 1 round-trip，mobile 友好，SSR 好寫
- Pro: Frontend 唔洗識 business rule（featured = page 0、new = page 1）
- Con: 耦合 frontend 需求入 backend（下一次要加 recommended section 就要改呢度）
- Con: Cache invalidation 複雜（3 個 data source 其中一個改咗，個 aggregate 都要 invalidate）

**Where to draw the line**：對於 **homepage / 商品詳情頁** 呢啲用戶感知明顯嘅頁面，BFF 值得。對於 admin 後台每個 section 都差唔多獨立 loading，就用 RESTful 就夠。

---

### 3. Web Vitals ingestion — 點解 frontend 唔直接 push Prometheus？

**Problem**：browser 冇 Prometheus client，同埋 Prometheus pull model，browser 冇 stable endpoint 俾 Prometheus 嚟 scrape。

**Solution**：frontend `sendBeacon()` 將 `web-vitals` 嘅 LCP/INP/CLS/FCP/TTFB POST 去 backend，backend 用 Micrometer 寫入 registry，Prometheus scrape backend 就間接攞到 frontend 數據。

```java
@PostMapping("/vitals")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void recordVitals(@Valid @RequestBody WebVitalsRequest req) {
    Timer timer = Timer.builder("frontend.vitals")
            .tag("name", req.getName())     // LCP|INP|CLS|FCP|TTFB
            .tag("rating", req.getRating()) // good|needs-improvement|poor
            .tag("page", req.getPage())
            .register(meterRegistry);
    timer.record(Duration.ofMillis(req.getValue().longValue()));
}
```

**DTO validation** 用 `@Pattern` 白名單 metric name 同 rating — 幫忙擋 label cardinality attack（如果 attacker 亂噏 `name=<random>` 會爆 Prometheus 記憶體）。

**Error ingestion** 同 pattern，用 `Counter` 因為 error 係 discrete event，唔係 duration。

**Grafana 度睇嘅 PromQL：**
```
histogram_quantile(0.95, rate(frontend_vitals_seconds_bucket{name="LCP"}[5m]))
```

---

### 4. OpenAPI spec — Machine-readable contract

**Problem**：frontend 寫 `type Product = { id: number; name: string; ... }` 同 backend `ProductResponse.java` 會 drift，對唔埋無 compile error。

**Solution**：`springdoc-openapi` 自動 derive OpenAPI 3 JSON 由 `@RestController` + `@RequestBody` + Jackson annotations。Frontend 用 `openapi-typescript` CI generate TS types，drift 即刻 build fail。

```java
@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
            .info(new Info().title("OnlineShopping API").version("1.0.0"))
            .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
            .components(new Components().addSecuritySchemes("bearerAuth",
                    new SecurityScheme().type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer").bearerFormat("JWT")));
    }
}
```

**Verify：**
```bash
curl -s http://localhost:8080/v3/api-docs | jq '.paths | keys'
# → ["/api/auth/login", "/api/feed", "/api/metrics/errors", "/api/metrics/vitals", ...]
```

Swagger UI 自動喺 `/swagger-ui.html` — 面試時 demo 得。

---

## 面试答法

### Q: "How would you let a browser from a different origin call your API safely?"

> **用 CORS，但要小心 3 個陷阱：**
>
> 1. **AllowCredentials + wildcard origin 唔夾**。如果我要送 cookie / Authorization，`Access-Control-Allow-Origin: *` 會俾 browser block — 要明文 list 所有 trusted origins。我 inject 成 `@Value` 讀 env var，`http://localhost:3000` dev，prod 就 `https://shop.example.com`。
>
> 2. **Exposed headers**。默認 browser JS 只可以讀 `Content-Type`、`Cache-Control` 幾個 "simple" response header。我哋有 `X-Trace-Id`，要 `setExposedHeaders()` 顯式 opt-in，frontend 先讀得到。
>
> 3. **Preflight caching**。`setMaxAge(3600)` 等 browser cache 1 小時，否則每個非 simple request 都雙倍 RTT。
>
> Security 方面要 call `http.cors(Customizer.withDefaults())` 先會喺 security filter chain 生效 — 好多人漏咗呢步，結果 preflight 俾 401 拒絕。

### Q: "Why add a BFF endpoint instead of letting the browser call N backend endpoints?"

> **主要 4 個原因：**
>
> 1. **Latency** — mobile 5G round-trip ~150ms，3 個 call 比 1 個 call 慢 2×。
> 2. **Caching coherence** — 3 個獨立 endpoint 返 3 個唔同 timestamp 嘅 data，可能 featured list 更新咗但 categories 係 stale，UI 呈現 inconsistent。1 個 aggregate 做得 snapshot-consistent。
> 3. **Frontend 唔識 business rule** — "featured = page 0 sort by createdAt desc" 唔應該 hardcode 喺 React component。Backend owns 呢個邏輯。
> 4. **API versioning** — BFF endpoint 係 frontend-owned contract，可以獨立 evolve 而唔影響 mobile app 直接用嘅 `/api/products`。
>
> **Trade-off：** BFF 會耦合 frontend 需求入 backend — 新加 section 要改 BFF。如果團隊分 BE/FE，要諗清楚 ownership。有啲 team 會將 BFF 放去 Next.js route handler（server-side），令 BE 繼續純 RESTful。

### Q: "How do you monitor frontend performance in production?"

> **三段 pipeline：**
>
> 1. **採集**：Next.js 用 `web-vitals` npm package，`sendBeacon('/api/metrics/vitals', ...)`。用 `sendBeacon` 唔用 `fetch` 因為 tab close 時 beacon 會 queue 落去，fetch 會 abort。
> 2. **Ingest**：Spring `MetricsController` 用 Micrometer `Timer` 寫入 registry，`@Pattern` validate metric name 白名單，防 label cardinality 爆 Prometheus memory。
> 3. **Visualize**：Prometheus scrape `/actuator/prometheus`，Grafana `histogram_quantile(0.95, rate(frontend_vitals_seconds_bucket{name="LCP"}[5m]))` 睇 p95 LCP。
>
> **對比 SaaS**：Datadog RUM / Sentry Performance 唔洗寫呢段 code，但每月幾千 USD。Self-host Prometheus + Grafana 免費但要自己 alert、retention、capacity planning。Startup / 小型 org 用 SaaS，大 org 為咗 PII 合規同 cost 會 self-host。

### Q: "How do you keep TypeScript types in sync with the Java backend?"

> **OpenAPI codegen：**
>
> 1. Backend `springdoc-openapi` 喺 `/v3/api-docs` expose JSON。
> 2. CI step 用 `openapi-typescript` 讀呢個 JSON generate `types/api.ts`。
> 3. Generated file commit 入 repo 或者 build-time 生成。
> 4. Frontend `import type { paths } from '@/types/api'` → 每個 endpoint、每個 DTO 都 type-safe。
> 5. Backend 改 `ProductResponse` 加 field，CI regenerate，frontend 未用新 field 就 warn，用錯 type 就 build fail。
>
> **Gotcha：** Lombok `@Data` 生成 getter 會 serialize 成 camelCase — OpenAPI schema 要對得上 frontend `api.paths['/api/feed'].get.responses['200'].content['application/json'].schema`。驗法：`mvn spring-boot:run` 後 `curl /v3/api-docs | jq '.components.schemas.FeedResponse'` 睇 field name。

---

## Practical deliverable

L25 分 10 個 commit：

| Commit | 內容 |
|---|---|
| `chore: add springdoc-openapi and prometheus registry` | pom.xml 加 2 個依賴 |
| `feat: enable OpenAPI spec and Prometheus actuator endpoint` | application.properties + `OpenApiConfig` + SecurityConfig permit |
| `feat: add CORS config for frontend origins (L25)` | `CorsConfig` + test profile + `@Profile("!test")` |
| `feat: add FeedResponse and CategoryResponse DTOs` | 2 個 Lombok DTO |
| `feat: add FeedService aggregating featured, new arrivals, categories` | `FeedService` + unit test（Mockito） |
| `feat: add GET /api/feed BFF aggregate endpoint` | `FeedController` + MockMvc integration test + SecurityConfig permit |
| `feat: add WebVitalsRequest DTO with validation` | DTO + `@Pattern` 白名單 |
| `feat: add POST /api/metrics/vitals ingesting Web Vitals into Prometheus` | `MetricsController` + 2 個 MockMvc test（204 + 400） |
| `feat: add POST /api/metrics/errors ingesting frontend errors` | `FrontendErrorRequest` DTO + `recordError()` + 2 個 test |
| `docs: add lesson 25 CORS + BFF + OpenAPI notes` | 本課 |

**Verify：**

```bash
# 全部 test green
mvn test
# → Tests run: 17, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS

# 起 app (需要 docker-compose up -d mysql redis rocketmq elasticsearch)
mvn spring-boot:run

# OpenAPI
curl -s http://localhost:8080/v3/api-docs | jq '.paths | keys'

# Prometheus
curl -s http://localhost:8080/actuator/prometheus | grep frontend_

# BFF endpoint
curl -s http://localhost:8080/api/feed | jq

# Vitals ingest
curl -X POST http://localhost:8080/api/metrics/vitals \
  -H "Content-Type: application/json" \
  -d '{"name":"LCP","value":2100,"rating":"good","page":"/"}' -w "%{http_code}\n"
# → 204

# CORS preflight (allowed)
curl -i -X OPTIONS http://localhost:8080/api/feed \
  -H "Origin: http://localhost:3000" \
  -H "Access-Control-Request-Method: GET"
# → Access-Control-Allow-Origin: http://localhost:3000

# CORS preflight (disallowed)
curl -i -X OPTIONS http://localhost:8080/api/feed \
  -H "Origin: http://evil.example.com" \
  -H "Access-Control-Request-Method: GET"
# → 403，無 Access-Control-Allow-Origin header
```

---

## Next lesson

**L26: Next.js App Router + BFF consumption**。我哋會用 `openapi-typescript` generate 呢課 expose 嘅 OpenAPI spec 去 `types/api.ts`，再寫一個 Next.js server component fetch `/api/feed` 渲染 homepage。
