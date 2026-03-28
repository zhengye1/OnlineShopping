# Lesson 4: Authentication & Authorization

**Date:** 2026-03-28

---

## 核心概念

### Authentication vs Authorization（面试必问）
- **Authentication（认证）** — 你系边个？验证身份（login）
- **Authorization（授权）** — 你可以做咩？验证权限

> "Authentication is like showing your ID at the door. Authorization is whether your ticket lets you into VIP."

### Session-based vs Token-based (JWT)

| | Session | JWT |
|---|---|---|
| State | Server存session（stateful） | Server唔存嘢（stateless） |
| 扩展性 | 多server要share session | 任何server都可以验证 |
| REST | ❌ 违反stateless | ✅ 符合REST |
| Mobile | ❌ Cookie唔方便 | ✅ Token放Header |

### JWT结构（面试必知）
```
Header.Payload.Signature

Header:    {"alg": "HS256", "typ": "JWT"}
Payload:   {"sub": "username", "role": "BUYER", "exp": 1234567890}
Signature: HMAC-SHA256(header + payload, secret_key)
```

**Payload唔系加密，只系Base64 encode。任何人都可以decode。Signature先系防篡改。**
**永远唔好放sensitive data喺JWT入面。**

### BCrypt Password Hashing
```java
// 注册：hash password
passwordEncoder.encode("mypassword")  → "$2a$10$xJk3..."

// 登录：verify password（唔系decode，系hash一次再比较）
passwordEncoder.matches("mypassword", "$2a$10$xJk3...")  → true
```

### Information Leakage Prevention
```java
"Invalid username or password"  // ✅ 唔讲具体原因
"Username not found"            // ❌ 攻击者知道username唔存在
```

### 401 vs 403
- **401 Unauthorized** — 未login（冇token或token invalid）
- **403 Forbidden** — login咗但冇权限

### Password Validation（Regex）
```java
@Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])...")
```
- `(?=...)` = lookahead，只check条件存唔存在
- 至少1大写 + 1小写 + 1数字 + 1特殊字符

### Spring Security Filter Chain
```
Request → JwtAuthenticationFilter → SecurityConfig rules → Controller
                │
                ├── 有valid token → set SecurityContext → 放行
                ├── 冇token + public endpoint → 放行
                └── 冇token + protected endpoint → 401
```

### Security Config重点
```java
.csrf(disable)                    // REST API用JWT唔用cookie
.sessionManagement(STATELESS)     // 唔创建HTTP session
.requestMatchers("/api/auth/**").permitAll()          // 公开
.requestMatchers(GET, "/api/products/**").permitAll()  // 公开
.requestMatchers(DELETE, "/api/products/**").hasRole("ADMIN")  // Admin only
.anyRequest().authenticated()                          // 其余要login
```

### Security Exception Handling
Security filter chain喺Spring MVC之外运行：
- 需要自己实现 `AuthenticationEntryPoint`（401）同 `AccessDeniedHandler`（403）
- 自己create `ObjectMapper` 时要手动 `.disable(WRITE_DATES_AS_TIMESTAMPS)`

---

## Authentication Flow

### Register
```
POST /api/auth/register
  → validate input (@Valid)
  → check username/email唔重复
  → BCrypt hash password
  → save user (default role: BUYER)
  → generate JWT token
  → return token + username + role
```

### Login
```
POST /api/auth/login
  → find user by username
  → BCrypt verify password
  → check account active
  → generate JWT token
  → return token + username + role
```

### Authenticated Request
```
Any protected endpoint
  → Header: "Authorization: Bearer <token>"
  → JwtAuthenticationFilter extract + validate token
  → set SecurityContext
  → Controller handles request
```

---

## 今日写既Code

### New Files
```
security/
├── JwtService.java                 ← generate/validate JWT tokens
├── JwtAuthenticationFilter.java    ← extract token from request header
└── SecurityExceptionHandler.java   ← 401/403 JSON responses
config/
└── SecurityConfig.java             ← security rules + BCrypt bean
dto/
├── RegisterRequest.java            ← with password regex validation
├── LoginRequest.java
└── AuthResponse.java               ← token + username + role
exception/
└── BadRequestException.java        ← for auth business errors
repository/
└── UserRepository.java             ← findByUsername, existsByUsername/Email
service/
└── AuthService.java                ← register + login logic
controller/
└── AuthController.java             ← POST /register, POST /login
```

### Dependencies Added
- `spring-boot-starter-security` — Spring Security
- `jjwt-api` / `jjwt-impl` / `jjwt-jackson` — JWT library

### Config Added
```properties
jwt.secret=${JWT_SECRET:base64_encoded_key}
jwt.expiration=86400000  # 24 hours
```

---

## 面试题自测

1. **「Authentication同Authorization有咩分别？」**
2. **「Session-based同Token-based auth有咩分别？点解选JWT？」**
3. **「JWT由咩组成？Payload系咪加密？」**
4. **「密码点样安全储存？BCrypt点work？」**
5. **「Login失败点解唔应该话'username not found'？」**
6. **「401同403有咩分别？」**
7. **「点解REST API要disable CSRF？」**
8. **「Spring Security filter chain点样运作？」**

---

## 下课预告：Lesson 5 — Product Module
- Product listing with pagination and sorting
- Search/filter by category, price range, keyword
- Seller assigns products (using auth context)
- Product image handling
