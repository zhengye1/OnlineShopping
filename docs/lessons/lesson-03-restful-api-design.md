# Lesson 3: RESTful API Design

**Date:** 2026-03-27

---

## 核心概念

### REST = Representational State Transfer

Principles:
1. **Resource-based** — URL系noun唔系verb（`/products` ✅，`/getProducts` ❌）
2. **HTTP Methods = 动作** — GET读取、POST创建、PUT更新、DELETE删除
3. **Stateless** — 每个request自包含所有信息
4. **Proper Status Codes** — 200 OK、201 Created、204 No Content、400 Bad Request、404 Not Found、500 Server Error

面试答法：
> "REST is an architectural style where URLs represent resources as nouns, HTTP methods define actions. It's stateless, and responses use proper HTTP status codes."

### DTO（Data Transfer Object）

点解唔直接用Entity做request/response：
1. **安全** — Entity有password等sensitive field
2. **灵活** — request同response可以唔同shape（例如request传categoryId，response传categoryName）
3. **解耦** — database schema改咗唔影响API contract

面试答法：
> "DTOs decouple the API contract from the persistence model. They prevent exposing sensitive fields, allow different shapes for request and response, and ensure database changes don't break the API."

### Validation — DTO层做

```java
@NotBlank   — String唔可以null/空/纯空格（NOT @NotNull for strings!）
@NotNull    — 唔可以null（用于non-String fields）
@Positive   — 必须 > 0
@Min(0)     — 必须 >= 0
@Email      — email格式
@Size       — 长度限制
```

`@NotBlank` vs `@NotNull`（面试常问）：
- `@NotNull` — 只check唔系null，"" 会pass
- `@NotBlank` — check唔系null、唔系""、唔系"   "

Controller参数加 `@Valid` 先会trigger validation：
```java
public ProductResponse createProduct(@Valid @RequestBody ProductRequest request)
```

### Global Error Handling — `@RestControllerAdvice`

统一exception处理，唔使每个Controller写try-catch：
```
Validation error (@Valid failed)     → 400 + field-level errors
ResourceNotFoundException            → 404 + specific message
Unexpected error                     → 500 + generic message
```

Custom exception好过RuntimeException：
- 可以针对性catch
- 自动map到正确status code
- 更readable

### Constructor Injection

```java
private final ProductService productService;  // private + final

public ProductController(ProductService productService) {
    this.productService = productService;
}
```

- `private` — 外部唔应该access
- `final` — inject后唔可reassign（immutability）
- 唔使写 `@Autowired`（constructor injection时可省略）

### Stream API — Entity to DTO conversion

```java
productRepository.findAll()
    .stream()                    // 转成stream
    .map(this::toResponse)       // 每个element转换
    .toList();                   // 收集成List
```

### REST Status Code Convention

```
GET    成功 → 200 OK
POST   成功 → 201 Created
PUT    成功 → 200 OK
DELETE 成功 → 204 No Content
```

### `@PathVariable` 用 `Long` 唔用 `long`

Wrapper type同Entity一致，primitive parse失败会有奇怪error。

---

## 今日写既Code

### Project Structure（新增）
```
com.onlineshopping/
├── dto/
│   ├── ProductRequest.java      ← input DTO + validation
│   ├── ProductResponse.java     ← output DTO
│   └── ErrorResponse.java       ← 统一error格式
├── exception/
│   ├── ResourceNotFoundException.java    ← custom 404 exception
│   └── GlobalExceptionHandler.java       ← @RestControllerAdvice
├── repository/
│   ├── ProductRepository.java   ← extends JpaRepository
│   └── CategoryRepository.java
├── service/
│   └── ProductService.java      ← CRUD business logic + Entity↔DTO conversion
└── controller/
    ├── HealthController.java
    └── ProductController.java   ← REST endpoints
```

### API Endpoints
```
GET    /api/products          → 200 + List<ProductResponse>
GET    /api/products/{id}     → 200 + ProductResponse (or 404)
POST   /api/products          → 201 + ProductResponse
PUT    /api/products/{id}     → 200 + ProductResponse (or 404)
DELETE /api/products/{id}     → 204 No Content (or 404)
```

### Dependencies Added
- `spring-boot-starter-validation` — Bean Validation (@NotBlank, @Positive等)

---

## 面试题自测

1. **「咩叫RESTful？最重要既principles系咩？」**
2. **「点解唔直接用Entity做API request/response？」**
3. **「`@NotBlank` 同 `@NotNull` 有咩分别？」**
4. **「点样统一处理API errors？」** — @RestControllerAdvice
5. **「Controller可唔可以直接call Repository？点解唔好？」**
6. **「`@Valid` 唔加会点？」** — validation annotations唔会生效
7. **「POST成功应该return咩status code？DELETE呢？」**

---

## 下课预告：Lesson 4 — Authentication & Authorization
- User registration + login
- BCrypt password hashing
- JWT token
- Spring Security basics
