# Lesson 25 — Backend Bridge (CORS + BFF + OpenAPI) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prepare the Spring Boot backend to serve the upcoming Next.js frontend by adding CORS, BFF-friendly aggregate endpoints, frontend metric ingestion, and OpenAPI spec generation — all as additive, non-breaking changes.

**Architecture:** Add four production-ready pieces: (1) `CorsConfig` wired into the existing `SecurityConfig` filter chain; (2) `FeedController` returning home-page aggregate in one request; (3) `MetricsController` ingesting Web Vitals and frontend errors into Prometheus via the existing `MeterRegistry`; (4) `springdoc-openapi` generating `/v3/api-docs` for TypeScript codegen in later lessons. Controller-level `MockMvc` tests establish a testing pattern not yet present in this repo.

**Tech Stack:** Java 21 · Spring Boot 3.5.13 · Spring Security 6 · Micrometer · `springdoc-openapi` 2.6+ · JUnit 5 · MockMvc · Mockito · Lombok

**Spec reference:** `docs/superpowers/specs/2026-04-17-onlineshopping-react-frontend-design.md` §6 Backend changes, §4.4 Error handling

---

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `pom.xml` | Modify | Add `springdoc-openapi-starter-webmvc-ui` + `micrometer-registry-prometheus` |
| `src/main/java/com/onlineshopping/config/CorsConfig.java` | Create | Reusable `CorsConfigurationSource` bean; env-driven allowed origins |
| `src/main/java/com/onlineshopping/config/SecurityConfig.java` | Modify | Enable CORS via `.cors(Customizer.withDefaults())`; permit `/api/feed`, `/api/metrics/**`, `/v3/api-docs/**`, `/swagger-ui/**` |
| `src/main/java/com/onlineshopping/dto/FeedResponse.java` | Create | Aggregate DTO: `featuredProducts`, `newArrivals`, `categories` |
| `src/main/java/com/onlineshopping/dto/CategoryResponse.java` | Create | Small DTO for category in feed (`id`, `name`) |
| `src/main/java/com/onlineshopping/service/FeedService.java` | Create | Compose featured + new arrivals + categories (delegates to existing repos) |
| `src/main/java/com/onlineshopping/controller/FeedController.java` | Create | `GET /api/feed` — returns `FeedResponse` |
| `src/main/java/com/onlineshopping/dto/WebVitalsRequest.java` | Create | Validated DTO: `name` (enum LCP/INP/CLS/FCP/TTFB), `value` (double), `rating` (good/needs-improvement/poor), `page` (string), `navigationType` |
| `src/main/java/com/onlineshopping/dto/FrontendErrorRequest.java` | Create | Validated DTO: `message`, `stack`, `url`, `userAgent`, `traceId` (nullable), `severity` |
| `src/main/java/com/onlineshopping/controller/MetricsController.java` | Create | `POST /api/metrics/vitals`, `POST /api/metrics/errors` — emit Micrometer metrics |
| `src/main/java/com/onlineshopping/config/OpenApiConfig.java` | Create | `@OpenAPIDefinition` with info + JWT security scheme |
| `src/main/resources/application.yml` (or `application.properties`) | Modify | Set `app.cors.allowed-origins` env-driven; enable prometheus actuator endpoint |
| `src/test/java/com/onlineshopping/controller/FeedControllerTest.java` | Create | MockMvc test |
| `src/test/java/com/onlineshopping/controller/MetricsControllerTest.java` | Create | MockMvc test |
| `src/test/java/com/onlineshopping/service/FeedServiceTest.java` | Create | Mockito test for aggregation logic |
| `src/test/java/com/onlineshopping/config/CorsConfigIntegrationTest.java` | Create | `@SpringBootTest` + MockMvc, verifies preflight + actual CORS headers |
| `docs/lessons/lesson-25-cors-bff-openapi.md` | Create | Lesson write-up |

**Naming discipline:**
- Endpoint paths: `/api/feed`, `/api/metrics/vitals`, `/api/metrics/errors` (follows existing `/api/*` convention — NOT `/api/v1/*`)
- Metric names: `frontend.vitals` (tagged by `name`, `rating`, `page`), `frontend.errors` (tagged by `severity`, `page`)
- DTO suffix: `Response` for read, `Request` for write (matches `ProductRequest`/`ProductResponse`)

---

## Task 1: Add dependencies

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1.1: Add two dependencies**

Add these to `<dependencies>` in `pom.xml` (alongside `spring-boot-starter-actuator`):

```xml
<!-- OpenAPI spec generation for frontend codegen -->
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.6.0</version>
</dependency>

<!-- Micrometer Prometheus registry (exposes /actuator/prometheus) -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
    <scope>runtime</scope>
</dependency>
```

- [ ] **Step 1.2: Verify build**

Run: `mvn -q -DskipTests compile`
Expected: `BUILD SUCCESS`

- [ ] **Step 1.3: Commit**

```bash
git add pom.xml
git commit -m "chore: add springdoc-openapi and prometheus registry for L25"
```

---

## Task 2: Enable Prometheus + OpenAPI endpoints in config

**Files:**
- Modify: `src/main/resources/application.yml` (or `.properties` — check which exists)
- Create: `src/main/java/com/onlineshopping/config/OpenApiConfig.java`

- [ ] **Step 2.1: Inspect existing config file**

Run: `ls src/main/resources/application*` — note whether `.yml` or `.properties` is used.

- [ ] **Step 2.2: Expose prometheus actuator endpoint**

If `application.yml`, add:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    prometheus:
      enabled: true
```

If `application.properties`, add:

```properties
management.endpoints.web.exposure.include=health,info,prometheus,metrics
management.endpoint.prometheus.enabled=true
```

- [ ] **Step 2.3: Create OpenAPI config**

Create `src/main/java/com/onlineshopping/config/OpenApiConfig.java`:

```java
package com.onlineshopping.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("OnlineShopping API")
                        .version("1.0.0")
                        .description("Backend API for OnlineShopping — consumed by Next.js BFF"))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME_NAME,
                        new SecurityScheme()
                                .name(SECURITY_SCHEME_NAME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
```

- [ ] **Step 2.4: Permit OpenAPI + actuator prometheus in SecurityConfig**

Open `src/main/java/com/onlineshopping/config/SecurityConfig.java`. In `authorizeHttpRequests`, add these BEFORE `.anyRequest().authenticated()`:

```java
.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
```

(Note: `/actuator/**` is already permitted at line 45.)

- [ ] **Step 2.5: Smoke-test the app**

Run: `mvn -q spring-boot:run` in one terminal, then in another:

```bash
curl -s http://localhost:8080/v3/api-docs | head -20
curl -s http://localhost:8080/actuator/prometheus | head -20
```

Expected: JSON spec + Prometheus text format metrics. Stop the app after verifying.

- [ ] **Step 2.6: Commit**

```bash
git add src/main/java/com/onlineshopping/config/OpenApiConfig.java \
        src/main/java/com/onlineshopping/config/SecurityConfig.java \
        src/main/resources/application.*
git commit -m "feat: enable OpenAPI spec and Prometheus actuator endpoint"
```

---

## Task 3: CORS configuration (test-first)

**Files:**
- Create: `src/test/java/com/onlineshopping/config/CorsConfigIntegrationTest.java`
- Create: `src/main/java/com/onlineshopping/config/CorsConfig.java`
- Modify: `src/main/java/com/onlineshopping/config/SecurityConfig.java`
- Modify: `src/main/resources/application.yml` (or `.properties`)

- [ ] **Step 3.1: Write failing preflight test**

Create `src/test/java/com/onlineshopping/config/CorsConfigIntegrationTest.java`:

```java
package com.onlineshopping.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.cors.allowed-origins=http://localhost:3000,https://shop.example.com"
})
class CorsConfigIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void preflight_from_allowed_origin_returns_204_with_cors_headers() throws Exception {
        mockMvc.perform(options("/api/products")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())  // Spring returns 200 for preflight by default
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().exists("Access-Control-Allow-Methods"))
                .andExpect(header().exists("Access-Control-Allow-Credentials"));
    }

    @Test
    void preflight_from_disallowed_origin_is_rejected() throws Exception {
        mockMvc.perform(options("/api/products")
                        .header("Origin", "http://evil.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 3.2: Run test — expect failure**

Run: `mvn -q test -Dtest=CorsConfigIntegrationTest`
Expected: FAIL (no `Access-Control-Allow-Origin` header; CORS not configured).

- [ ] **Step 3.3: Create CorsConfig**

Create `src/main/java/com/onlineshopping/config/CorsConfig.java`:

```java
package com.onlineshopping.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class CorsConfig {

    @Value("${app.cors.allowed-origins:http://localhost:3000}")
    private List<String> allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With",
                                        "X-Trace-Id", "Cookie"));
        config.setExposedHeaders(List.of("X-Trace-Id"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
```

- [ ] **Step 3.4: Wire CORS into SecurityConfig**

In `SecurityConfig.java`, modify the `securityFilterChain` method — add `.cors(Customizer.withDefaults())` immediately after `http`. Final chain should start:

```java
import org.springframework.security.config.Customizer;

http
    .cors(Customizer.withDefaults())   // <-- add this line
    .csrf(AbstractHttpConfigurer::disable)
    ...
```

- [ ] **Step 3.5: Add default config property**

In `application.yml`:

```yaml
app:
  cors:
    allowed-origins: ${APP_CORS_ALLOWED_ORIGINS:http://localhost:3000}
```

Or `application.properties`:

```properties
app.cors.allowed-origins=${APP_CORS_ALLOWED_ORIGINS:http://localhost:3000}
```

- [ ] **Step 3.6: Run test — expect pass**

Run: `mvn -q test -Dtest=CorsConfigIntegrationTest`
Expected: PASS (both tests green).

- [ ] **Step 3.7: Commit**

```bash
git add src/main/java/com/onlineshopping/config/CorsConfig.java \
        src/main/java/com/onlineshopping/config/SecurityConfig.java \
        src/test/java/com/onlineshopping/config/CorsConfigIntegrationTest.java \
        src/main/resources/application.*
git commit -m "feat: add CORS config for frontend origins (L25)"
```

---

## Task 4: `CategoryResponse` + `FeedResponse` DTOs

**Files:**
- Create: `src/main/java/com/onlineshopping/dto/CategoryResponse.java`
- Create: `src/main/java/com/onlineshopping/dto/FeedResponse.java`

- [ ] **Step 4.1: Create CategoryResponse**

```java
package com.onlineshopping.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CategoryResponse {
    private Long id;
    private String name;
}
```

- [ ] **Step 4.2: Create FeedResponse**

```java
package com.onlineshopping.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FeedResponse {
    private List<ProductResponse> featuredProducts;
    private List<ProductResponse> newArrivals;
    private List<CategoryResponse> categories;
}
```

- [ ] **Step 4.3: Compile check**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4.4: Commit**

```bash
git add src/main/java/com/onlineshopping/dto/CategoryResponse.java \
        src/main/java/com/onlineshopping/dto/FeedResponse.java
git commit -m "feat: add FeedResponse and CategoryResponse DTOs"
```

---

## Task 5: `FeedService` (TDD)

**Files:**
- Create: `src/test/java/com/onlineshopping/service/FeedServiceTest.java`
- Create: `src/main/java/com/onlineshopping/service/FeedService.java`

- [ ] **Step 5.1: Inspect `PageResponse` accessors**

Run: `cat src/main/java/com/onlineshopping/dto/PageResponse.java`
Note the constructor signature and getter for the list payload (could be `getContent()`, `getData()`, etc.). Use the actual names below.

- [ ] **Step 5.2: Write failing test**

```java
package com.onlineshopping.service;

import com.onlineshopping.dto.FeedResponse;
import com.onlineshopping.dto.PageResponse;
import com.onlineshopping.dto.ProductResponse;
import com.onlineshopping.model.Category;
import com.onlineshopping.repository.CategoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedServiceTest {

    @Mock ProductService productService;
    @Mock CategoryRepository categoryRepository;
    @InjectMocks FeedService feedService;

    @Test
    void getFeed_returns_featured_newArrivals_and_categories() {
        ProductResponse p1 = new ProductResponse();
        p1.setId(1L);
        ProductResponse p2 = new ProductResponse();
        p2.setId(2L);

        // Adjust PageResponse construction based on Step 5.1 inspection.
        // Example assumes (content, total, totalPages, page, size):
        PageResponse<ProductResponse> featured = new PageResponse<>(
                List.of(p1), 1L, 1, 0, 8);
        PageResponse<ProductResponse> newArrivals = new PageResponse<>(
                List.of(p2), 1L, 1, 0, 8);

        when(productService.getAllProducts(0, 8, "createdAt", "desc"))
                .thenReturn(featured);
        when(productService.getAllProducts(1, 8, "createdAt", "desc"))
                .thenReturn(newArrivals);

        Category cat = new Category();
        cat.setId(10L);
        cat.setName("Electronics");
        when(categoryRepository.findAll()).thenReturn(List.of(cat));

        FeedResponse result = feedService.getFeed();

        assertEquals(1, result.getFeaturedProducts().size());
        assertEquals(1L, result.getFeaturedProducts().get(0).getId());
        assertEquals(1, result.getNewArrivals().size());
        assertEquals(2L, result.getNewArrivals().get(0).getId());
        assertEquals(1, result.getCategories().size());
        assertEquals("Electronics", result.getCategories().get(0).getName());
    }
}
```

- [ ] **Step 5.3: Run test — expect compile failure**

Run: `mvn -q test -Dtest=FeedServiceTest`
Expected: FAIL — `FeedService` class doesn't exist.

- [ ] **Step 5.4: Implement FeedService**

Substitute the actual `PageResponse` content-getter name from Step 5.1.

```java
package com.onlineshopping.service;

import com.onlineshopping.dto.CategoryResponse;
import com.onlineshopping.dto.FeedResponse;
import com.onlineshopping.dto.ProductResponse;
import com.onlineshopping.dto.PageResponse;
import com.onlineshopping.repository.CategoryRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class FeedService {

    private static final int PAGE_SIZE = 8;

    private final ProductService productService;
    private final CategoryRepository categoryRepository;

    public FeedService(ProductService productService,
                       CategoryRepository categoryRepository) {
        this.productService = productService;
        this.categoryRepository = categoryRepository;
    }

    public FeedResponse getFeed() {
        PageResponse<ProductResponse> featured =
                productService.getAllProducts(0, PAGE_SIZE, "createdAt", "desc");
        PageResponse<ProductResponse> newArrivals =
                productService.getAllProducts(1, PAGE_SIZE, "createdAt", "desc");

        List<CategoryResponse> categories = categoryRepository.findAll().stream()
                .map(c -> new CategoryResponse(c.getId(), c.getName()))
                .toList();

        // Use the actual PageResponse content-getter name (e.g. getContent() / getData() / getItems())
        return new FeedResponse(
                featured.getContent(),
                newArrivals.getContent(),
                categories);
    }
}
```

- [ ] **Step 5.5: Run test — expect pass**

Run: `mvn -q test -Dtest=FeedServiceTest`
Expected: PASS.

- [ ] **Step 5.6: Commit**

```bash
git add src/main/java/com/onlineshopping/service/FeedService.java \
        src/test/java/com/onlineshopping/service/FeedServiceTest.java
git commit -m "feat: add FeedService aggregating featured, new arrivals, categories"
```

---

## Task 6: `FeedController` with MockMvc test

**Files:**
- Create: `src/test/java/com/onlineshopping/controller/FeedControllerTest.java`
- Create: `src/main/java/com/onlineshopping/controller/FeedController.java`
- Modify: `src/main/java/com/onlineshopping/config/SecurityConfig.java` (permit `/api/feed`)

- [ ] **Step 6.1: Write failing MockMvc test**

```java
package com.onlineshopping.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onlineshopping.dto.CategoryResponse;
import com.onlineshopping.dto.FeedResponse;
import com.onlineshopping.dto.ProductResponse;
import com.onlineshopping.service.FeedService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(FeedController.class)
@Import({com.onlineshopping.config.SecurityConfig.class,
         com.onlineshopping.config.CorsConfig.class})
class FeedControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean FeedService feedService;

    // SecurityConfig collaborators — mock these so the context loads
    @MockBean com.onlineshopping.security.JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean com.onlineshopping.security.SecurityExceptionHandler securityExceptionHandler;
    @MockBean com.onlineshopping.config.RateLimitFilter rateLimitFilter;

    @Test
    @WithAnonymousUser
    void getFeed_returns_200_with_aggregate_payload() throws Exception {
        ProductResponse p = new ProductResponse();
        p.setId(1L);
        p.setName("Phone");

        FeedResponse feed = new FeedResponse(
                List.of(p),
                List.of(p),
                List.of(new CategoryResponse(10L, "Electronics")));

        when(feedService.getFeed()).thenReturn(feed);

        mockMvc.perform(get("/api/feed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featuredProducts[0].id").value(1))
                .andExpect(jsonPath("$.featuredProducts[0].name").value("Phone"))
                .andExpect(jsonPath("$.newArrivals[0].id").value(1))
                .andExpect(jsonPath("$.categories[0].id").value(10))
                .andExpect(jsonPath("$.categories[0].name").value("Electronics"));
    }
}
```

Note: The `@Import` + `@MockBean` triplet for `JwtAuthenticationFilter`, `SecurityExceptionHandler`, `RateLimitFilter` is required because `SecurityConfig` injects them. If this proves brittle across tests, extract a `@TestConfiguration` helper in a later task. For now, copy this pattern per controller test.

- [ ] **Step 6.2: Run test — expect failure**

Run: `mvn -q test -Dtest=FeedControllerTest`
Expected: FAIL — `FeedController` doesn't exist.

- [ ] **Step 6.3: Implement FeedController**

```java
package com.onlineshopping.controller;

import com.onlineshopping.dto.FeedResponse;
import com.onlineshopping.service.FeedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/feed")
@Tag(name = "Feed", description = "Aggregate BFF endpoints for the home page")
public class FeedController {

    private final FeedService feedService;

    public FeedController(FeedService feedService) {
        this.feedService = feedService;
    }

    @GetMapping
    @Operation(summary = "Get home-page feed",
               description = "Returns featured products, new arrivals, and all categories in a single call")
    public FeedResponse getFeed() {
        return feedService.getFeed();
    }
}
```

- [ ] **Step 6.4: Permit `/api/feed` as public in SecurityConfig**

In `SecurityConfig.java`, inside `authorizeHttpRequests`, add BEFORE `.anyRequest().authenticated()`:

```java
.requestMatchers(HttpMethod.GET, "/api/feed").permitAll()
```

- [ ] **Step 6.5: Run test — expect pass**

Run: `mvn -q test -Dtest=FeedControllerTest`
Expected: PASS.

- [ ] **Step 6.6: Commit**

```bash
git add src/main/java/com/onlineshopping/controller/FeedController.java \
        src/main/java/com/onlineshopping/config/SecurityConfig.java \
        src/test/java/com/onlineshopping/controller/FeedControllerTest.java
git commit -m "feat: add GET /api/feed BFF aggregate endpoint"
```

---

## Task 7: `WebVitalsRequest` DTO

**Files:**
- Create: `src/main/java/com/onlineshopping/dto/WebVitalsRequest.java`

- [ ] **Step 7.1: Create DTO with validation**

```java
package com.onlineshopping.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class WebVitalsRequest {

    @NotBlank
    @Pattern(regexp = "LCP|INP|CLS|FCP|TTFB",
             message = "name must be one of LCP, INP, CLS, FCP, TTFB")
    private String name;

    @NotNull
    private Double value;

    @NotBlank
    @Pattern(regexp = "good|needs-improvement|poor",
             message = "rating must be one of good, needs-improvement, poor")
    private String rating;

    @NotBlank
    private String page;

    private String navigationType;  // nullable — not all browsers send this
}
```

- [ ] **Step 7.2: Compile check**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 7.3: Commit**

```bash
git add src/main/java/com/onlineshopping/dto/WebVitalsRequest.java
git commit -m "feat: add WebVitalsRequest DTO with validation"
```

---

## Task 8: `MetricsController` — vitals endpoint (TDD)

**Files:**
- Create: `src/test/java/com/onlineshopping/controller/MetricsControllerTest.java`
- Create: `src/main/java/com/onlineshopping/controller/MetricsController.java`
- Modify: `src/main/java/com/onlineshopping/config/SecurityConfig.java` (permit `/api/metrics/**`)

- [ ] **Step 8.1: Write failing test for vitals endpoint**

```java
package com.onlineshopping.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onlineshopping.dto.WebVitalsRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MetricsController.class)
@Import({com.onlineshopping.config.SecurityConfig.class,
         com.onlineshopping.config.CorsConfig.class,
         MetricsControllerTest.TestMeterRegistryConfig.class})
class MetricsControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired MeterRegistry meterRegistry;

    @MockBean com.onlineshopping.security.JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean com.onlineshopping.security.SecurityExceptionHandler securityExceptionHandler;
    @MockBean com.onlineshopping.config.RateLimitFilter rateLimitFilter;

    @TestConfiguration
    static class TestMeterRegistryConfig {
        @Bean
        MeterRegistry testMeterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Test
    void postVitals_records_metric_and_returns_204() throws Exception {
        WebVitalsRequest req = new WebVitalsRequest();
        req.setName("LCP");
        req.setValue(2100.5);
        req.setRating("good");
        req.setPage("/");

        mockMvc.perform(post("/api/metrics/vitals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());

        assertThat(meterRegistry.get("frontend.vitals")
                .tag("name", "LCP")
                .tag("rating", "good")
                .tag("page", "/")
                .timer()
                .count()).isEqualTo(1L);
    }

    @Test
    void postVitals_with_invalid_name_returns_400() throws Exception {
        WebVitalsRequest req = new WebVitalsRequest();
        req.setName("INVALID");
        req.setValue(100.0);
        req.setRating("good");
        req.setPage("/");

        mockMvc.perform(post("/api/metrics/vitals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 8.2: Run test — expect failure**

Run: `mvn -q test -Dtest=MetricsControllerTest`
Expected: FAIL — controller doesn't exist.

- [ ] **Step 8.3: Implement MetricsController (vitals only for now)**

```java
package com.onlineshopping.controller;

import com.onlineshopping.dto.WebVitalsRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/api/metrics")
@Tag(name = "Metrics", description = "Frontend metric ingestion (Web Vitals, errors)")
public class MetricsController {

    private final MeterRegistry meterRegistry;

    public MetricsController(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostMapping("/vitals")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recordVitals(@Valid @RequestBody WebVitalsRequest req) {
        Timer timer = Timer.builder("frontend.vitals")
                .tag("name", req.getName())
                .tag("rating", req.getRating())
                .tag("page", req.getPage())
                .register(meterRegistry);
        timer.record(Duration.ofMillis(req.getValue().longValue()));
    }
}
```

Note on metric choice: Timer is used because LCP/INP/FCP/TTFB are durations in ms. CLS is unitless; it still works as a duration value but if you want strict correctness, branch on `req.getName()` and use `DistributionSummary` for CLS. For L25 Timer suffices — the lesson will discuss the trade-off.

- [ ] **Step 8.4: Permit `/api/metrics/**` in SecurityConfig**

In `SecurityConfig.java`, add BEFORE `.anyRequest().authenticated()`:

```java
.requestMatchers(HttpMethod.POST, "/api/metrics/**").permitAll()
```

- [ ] **Step 8.5: Run test — expect pass**

Run: `mvn -q test -Dtest=MetricsControllerTest`
Expected: PASS.

- [ ] **Step 8.6: Commit**

```bash
git add src/main/java/com/onlineshopping/controller/MetricsController.java \
        src/main/java/com/onlineshopping/config/SecurityConfig.java \
        src/test/java/com/onlineshopping/controller/MetricsControllerTest.java
git commit -m "feat: add POST /api/metrics/vitals ingesting Web Vitals into Prometheus"
```

---

## Task 9: `FrontendErrorRequest` DTO + errors endpoint (TDD)

**Files:**
- Create: `src/main/java/com/onlineshopping/dto/FrontendErrorRequest.java`
- Modify: `src/main/java/com/onlineshopping/controller/MetricsController.java`
- Modify: `src/test/java/com/onlineshopping/controller/MetricsControllerTest.java`

- [ ] **Step 9.1: Create DTO**

```java
package com.onlineshopping.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class FrontendErrorRequest {

    @NotBlank
    @Size(max = 500)
    private String message;

    @Size(max = 5000)
    private String stack;           // nullable

    @NotBlank
    @Size(max = 500)
    private String url;

    @NotBlank
    @Size(max = 500)
    private String userAgent;

    @Size(max = 100)
    private String traceId;         // nullable — set by BFF if available

    @NotBlank
    @Pattern(regexp = "fatal|error|warning|info",
             message = "severity must be one of fatal, error, warning, info")
    private String severity;
}
```

- [ ] **Step 9.2: Add failing test for errors endpoint**

Append to `MetricsControllerTest.java` inside the class:

```java
@Test
void postErrors_increments_counter_and_returns_204() throws Exception {
    com.onlineshopping.dto.FrontendErrorRequest err = new com.onlineshopping.dto.FrontendErrorRequest();
    err.setMessage("TypeError: cannot read property foo of undefined");
    err.setStack("at ProductCard (ProductCard.tsx:42)");
    err.setUrl("https://shop.example.com/products/1");
    err.setUserAgent("Mozilla/5.0 ...");
    err.setSeverity("error");

    mockMvc.perform(post("/api/metrics/errors")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(err)))
            .andExpect(status().isNoContent());

    assertThat(meterRegistry.get("frontend.errors")
            .tag("severity", "error")
            .counter()
            .count()).isEqualTo(1.0);
}

@Test
void postErrors_with_missing_message_returns_400() throws Exception {
    com.onlineshopping.dto.FrontendErrorRequest err = new com.onlineshopping.dto.FrontendErrorRequest();
    err.setUrl("https://shop.example.com/");
    err.setUserAgent("Mozilla/5.0");
    err.setSeverity("error");
    // missing message

    mockMvc.perform(post("/api/metrics/errors")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(err)))
            .andExpect(status().isBadRequest());
}
```

- [ ] **Step 9.3: Run test — expect failure**

Run: `mvn -q test -Dtest=MetricsControllerTest#postErrors_increments_counter_and_returns_204`
Expected: FAIL (404 / method not found).

- [ ] **Step 9.4: Extend MetricsController with errors endpoint**

Add to `MetricsController.java`:

```java
import com.onlineshopping.dto.FrontendErrorRequest;
import io.micrometer.core.instrument.Counter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// inside class:
private static final Logger log = LoggerFactory.getLogger(MetricsController.class);

@PostMapping("/errors")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void recordError(@Valid @RequestBody FrontendErrorRequest req) {
    Counter.builder("frontend.errors")
            .tag("severity", req.getSeverity())
            .register(meterRegistry)
            .increment();

    log.warn("frontend_error severity={} url={} message={} traceId={}",
            req.getSeverity(), req.getUrl(), req.getMessage(), req.getTraceId());
}
```

- [ ] **Step 9.5: Run full test class — expect pass**

Run: `mvn -q test -Dtest=MetricsControllerTest`
Expected: PASS (all 4 tests green).

- [ ] **Step 9.6: Commit**

```bash
git add src/main/java/com/onlineshopping/dto/FrontendErrorRequest.java \
        src/main/java/com/onlineshopping/controller/MetricsController.java \
        src/test/java/com/onlineshopping/controller/MetricsControllerTest.java
git commit -m "feat: add POST /api/metrics/errors ingesting frontend errors"
```

---

## Task 10: End-to-end smoke test via Spring Boot

**Files:**
- No new code — this is a manual verification checkpoint.

- [ ] **Step 10.1: Run full test suite**

Run: `mvn -q test`
Expected: BUILD SUCCESS, all tests green. If existing tests fail due to new config, fix before proceeding.

- [ ] **Step 10.2: Start the app**

Run: `mvn -q spring-boot:run` (or the usual dev command — check `docker-compose.dev.yml` if app depends on mysql/redis and start those first with `docker-compose up -d mysql redis`).

- [ ] **Step 10.3: Hit each new endpoint**

In a second terminal, run each and record the result:

```bash
# OpenAPI spec
curl -s http://localhost:8080/v3/api-docs | head -5
# Expected: JSON spec starting with {"openapi":"3.x..."}

# Prometheus endpoint
curl -s http://localhost:8080/actuator/prometheus | grep -c "^#"
# Expected: non-zero count of comment lines

# Feed endpoint (public)
curl -s http://localhost:8080/api/feed | head -50
# Expected: JSON with featuredProducts, newArrivals, categories arrays

# Vitals ingestion (public)
curl -s -X POST http://localhost:8080/api/metrics/vitals \
  -H "Content-Type: application/json" \
  -d '{"name":"LCP","value":2100,"rating":"good","page":"/"}' \
  -w "%{http_code}\n" -o /dev/null
# Expected: 204

# Error ingestion (public)
curl -s -X POST http://localhost:8080/api/metrics/errors \
  -H "Content-Type: application/json" \
  -d '{"message":"smoke test","url":"https://x","userAgent":"curl","severity":"info"}' \
  -w "%{http_code}\n" -o /dev/null
# Expected: 204

# Verify the metric showed up
curl -s http://localhost:8080/actuator/prometheus | grep frontend_
# Expected: frontend_vitals_*, frontend_errors_total lines
```

- [ ] **Step 10.4: CORS preflight smoke test**

```bash
curl -i -X OPTIONS http://localhost:8080/api/feed \
  -H "Origin: http://localhost:3000" \
  -H "Access-Control-Request-Method: GET"
```

Expected headers in response:
- `Access-Control-Allow-Origin: http://localhost:3000`
- `Access-Control-Allow-Methods: ...`
- `Access-Control-Allow-Credentials: true`

Try again with a disallowed origin:

```bash
curl -i -X OPTIONS http://localhost:8080/api/feed \
  -H "Origin: http://evil.example.com" \
  -H "Access-Control-Request-Method: GET"
```

Expected: no `Access-Control-Allow-Origin` header, 403.

- [ ] **Step 10.5: Stop the app; no commit needed**

Verification only — no code changes.

---

## Task 11: Write Lesson 25 markdown

**Files:**
- Create: `docs/lessons/lesson-25-cors-bff-openapi.md`

- [ ] **Step 11.1: Review existing lesson format**

Run: `head -80 docs/lessons/lesson-20-monitoring.md` — confirm the sections used: `## 核心概念`, `面试答法`, code blocks, etc.

- [ ] **Step 11.2: Draft lesson markdown**

Create `docs/lessons/lesson-25-cors-bff-openapi.md` with these sections (fill with the code you just wrote — keep it concrete, no placeholders):

1. **标题 + Date** — `# Lesson 25: CORS + BFF Pattern + OpenAPI` with date `2026-04-17`
2. **核心概念**
   - CORS: same-origin policy, preflight, why browsers do it, what headers mean — paste the `CorsConfig` code and walk through each setter
   - BFF (Backend-for-Frontend) pattern: problem (waterfall requests from browser), solution (aggregate endpoint on server), trade-off (coupling frontend needs into backend) — paste `FeedController` + `FeedService` code, explain `getFeed()`
   - Web Vitals ingestion: why frontend can't push to Prometheus directly, why we proxy through backend — paste `MetricsController` code
   - OpenAPI spec: machine-readable contract, why codegen beats hand-written types — paste `OpenApiConfig`, show `curl /v3/api-docs`
3. **面试答法** (每個 topic 一段):
   - *"How would you let a browser from a different origin call your API safely?"* → CORS, `allowCredentials`, why wildcard `*` origin breaks when credentials are required
   - *"Why add a BFF endpoint instead of letting the browser call N backend endpoints?"* → latency, mobile flakiness, caching, versioning, security
   - *"How do you monitor frontend performance in production?"* → Web Vitals → beacon API → backend → Prometheus → Grafana; contrast with RUM SaaS (Datadog, Sentry Performance)
   - *"How do you keep your TypeScript types in sync with the Java backend?"* → OpenAPI codegen in CI; build fails on drift
4. **Practical deliverable** — link to the 4 commits + the `mvn test` + `curl` verification steps; include the screenshot/log paste of `curl /api/feed`

Keep the tone matching existing lessons (Cantonese/English mix, `核心概念 + 面试答法` structure).

- [ ] **Step 11.3: Commit**

```bash
git add docs/lessons/lesson-25-cors-bff-openapi.md
git commit -m "docs: add lesson 25 CORS + BFF + OpenAPI notes"
```

---

## Task 12: Final verification — green build + all commits landed

- [ ] **Step 12.1: Full test run**

Run: `mvn -q clean verify`
Expected: BUILD SUCCESS, all tests pass, no new warnings beyond existing ones.

- [ ] **Step 12.2: Check git log**

Run: `git log --oneline -15`
Expected: see this sequence of commits (order may vary slightly):

```
docs: add lesson 25 CORS + BFF + OpenAPI notes
feat: add POST /api/metrics/errors ingesting frontend errors
feat: add POST /api/metrics/vitals ingesting Web Vitals into Prometheus
feat: add WebVitalsRequest DTO with validation
feat: add GET /api/feed BFF aggregate endpoint
feat: add FeedService aggregating featured, new arrivals, categories
feat: add FeedResponse and CategoryResponse DTOs
feat: add CORS config for frontend origins (L25)
feat: enable OpenAPI spec and Prometheus actuator endpoint
chore: add springdoc-openapi and prometheus registry for L25
docs: add React frontend design spec
docs: add lesson 24 mock interview notes
```

- [ ] **Step 12.3: Self-review checklist**

- [ ] All endpoints return expected status codes (`curl` outputs match)
- [ ] Existing endpoints still work (hit `/api/products` unchanged)
- [ ] `mvn test` green
- [ ] `/v3/api-docs` returns valid JSON
- [ ] `/actuator/prometheus` exposes `frontend_vitals_*` and `frontend_errors_*` after smoke test
- [ ] Lesson markdown reads well end-to-end

- [ ] **Step 12.4: Open PR (only when user asks)**

Per user's global git-workflow rule ("NEVER commit changes unless the user explicitly asks you to"), do NOT push / open PR automatically. Hand off to user with a summary of the 10 commits and their verification log. User will run:

```bash
git push -u origin <current-branch>
gh pr create --title "Lesson 25: CORS + BFF + OpenAPI backend bridge" --body "..."
```

---

## Self-Review Notes (pre-execution sanity check)

**Spec coverage check:**
- §6 CORS config → Task 3 ✓
- §6 Aggregate feed endpoint → Tasks 4-6 ✓
- §6 Web Vitals ingest → Tasks 7-8 ✓
- §6 Error ingest → Task 9 ✓
- §6 OpenAPI spec → Tasks 1-2 ✓
- §4.4 Error ingestion path → Task 9 ✓
- §8 Testing discipline (TDD) → every task follows RED → GREEN → refactor ✓

**Deferred (intentionally not in this plan, will come in later phases):**
- Frontend consumption of `/api/feed` → Lesson 32 (Phase 2)
- OpenAPI → TypeScript codegen → Lesson 26 (Phase 1 frontend setup)
- `/api/v1/` URL prefix (spec mentioned; not applied — existing code uses `/api/*`; defer any versioning decision)

**Type consistency check:**
- `FeedResponse.featuredProducts` ↔ Task 5 `featured.<contentGetter>()` (same field name maintained)
- `MeterRegistry` injection pattern ↔ existing `OrderService` pattern (constructor injection)
- Controller class naming ↔ existing pattern (`XxxController`, `@RestController`, `@RequestMapping("/api/xxx")`)

**Risks during execution:**
- `PageResponse` constructor/accessor may differ from Task 5's assumption → Step 5.1 forces engineer to inspect the file first; adjust test data and controller code accordingly. This is the ONE place ambiguity remains — resolved at execution time by a 1-minute file read.
- SecurityConfig `@Import` + `@MockBean` pattern may need a shared `@TestConfiguration` if more controller tests are added later → flagged in Task 6 note but not solved now (YAGNI).
- `application.yml` vs `.properties` — both paths covered in Task 2.1 and 3.5.

No other ambiguity. Plan is ready.
