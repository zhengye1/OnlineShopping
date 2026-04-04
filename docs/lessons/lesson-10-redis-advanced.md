# Lesson 10: Redis Advanced

**Date:** 2026-04-03

---

## 核心概念

### 1. Distributed Lock（分布式鎖）

#### 問題：Race Condition（超賣）

```
User A: 讀庫存 = 1 → 扣庫存 → stock = 0 ✓
User B: 讀庫存 = 1 → 扣庫存 → stock = -1 ✗  （同時讀到1，都以為有貨）
```

兩個 request 同時讀到 stock = 1，各自扣減，結果超賣。

#### 解決方案：Redis SET NX EX

```java
// SET key "locked" NX EX 10
// NX = 只有 key 不存在才設置（Not eXists）
// EX = 過期時間（秒）
// Redis 單線程 → 原子操作 → 只有一個人拿到鎖

public boolean tryLock(String key, long expireSeconds) {
    return Boolean.TRUE.equals(redisTemplate.opsForValue()
            .setIfAbsent(key, "locked", expireSeconds, TimeUnit.SECONDS));
}

public void unlock(String key) {
    redisTemplate.delete(key);
}
```

#### 使用方式（Checkout 扣庫存）

```java
String lockKey = "lock:product:" + product.getId();
boolean locked = redisService.tryLock(lockKey, 10);
if (!locked) throw new BadRequestException("System busy, please try again");
try {
    // 扣庫存邏輯（在鎖裡面做）
    if (product.getStock() < item.getQuantity())
        throw new BadRequestException("Not enough stock");
    product.setStock(product.getStock() - item.getQuantity());
} finally {
    redisService.unlock(lockKey);  // 一定要釋放鎖
}
```

#### 重點

| 問題 | 答案 |
|------|------|
| 點解用 Redis 唔用 Java synchronized？ | synchronized 只鎖一個 JVM。多個 server 部署時無效 |
| 點解要設 expire time？ | 防止 server crash 後鎖永遠唔釋放（deadlock） |
| expire 設幾耐？ | 太長 → 用戶等；太短 → 業務未完成鎖就過期，race condition 仍可能出現 |
| 點解 try-finally？ | 確保無論成功失敗都釋放鎖 |
| 鎖嘅粒度？ | 鎖 product ID，唔係鎖整個 checkout。買 product 1 唔影響買 product 2 |

---

### 2. Rate Limiting（限流）

#### 問題：惡意請求 / DDoS

Server 有容量上限，瘋狂發 request 會導致 502/503。需要限制每個用戶嘅請求頻率。

#### 固定窗口算法（Fixed Window）

```
Window: 60秒
Max: 30次

|------- 60s -------|------- 60s -------|
| 1  2  3 ... 30 ✓  |  1  2  3 ...     |
|         31 ✗ 429  |                   |
```

#### 實現：Redis INCR + EXPIRE

```java
public boolean isRateLimited(String key, long maxRequests, long windowSeconds) {
    // 1. INCR key（原子操作，返回遞增後的值）
    Long count = redisTemplate.opsForValue().increment(key);
    // 2. 如果值 == 1（第一次），設 TTL 開始計時
    if (count != null && count == 1)
        redisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
    // 3. 如果值 > maxRequests → 被限流
    return count != null && count > maxRequests;
}
```

#### 重點

| 問題 | 答案 |
|------|------|
| 點解只喺 count == 1 時設 TTL？ | key 第一次被創建時無 TTL，之後 TTL 已經在倒計時，重設會重置窗口 |
| 點解唔用 `set()` 設 TTL？ | `set()` 會覆蓋值，INCR 後變成 string，下次 INCR 會 error |
| Fixed Window 缺點？ | 邊界突發：第 59 秒 30 次 + 第 61 秒 30 次 = 2 秒內 60 次，全部通過 |
| 更好嘅方案？ | Sliding Window（滑動窗口），但固定窗口夠用 90% 場景 |

---

### 3. Filter Chain（過濾器鏈）

#### RateLimitFilter

```java
@Component
public class RateLimitFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) {
        String ip = request.getRemoteAddr();
        boolean isLimited = redisService.isRateLimited("rate:" + ip, 30, 60);
        if (isLimited) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());  // 429
            return;  // 唔好再行落去！
        }
        filterChain.doFilter(request, response);  // 放行
    }
}
```

#### Filter 順序（面試重點）

```
Request → RateLimitFilter → JwtFilter → UsernamePasswordFilter → Controller
              ↓                  ↓
         429 Too Many       401 Unauthorized
```

**原則：最平（cheapest）嘅檢查擺最前面。**

Rate Limit 只係 Redis INCR（<1ms），JWT 要 decode + verify。如果惡意 request 先過 JWT，浪費資源。

```java
// SecurityConfig 配置順序
.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
.addFilterBefore(rateLimitFilter, jwtAuthenticationFilter.getClass())
// 結果：RateLimit → JWT → UsernamePassword
```

---

### 4. Redis 掛咗會點？

三個功能同時受影響：

| 功能 | 影響 | 嚴重程度 |
|------|------|---------|
| Cache | 所有 cache miss → 全部 hit DB → DB 壓力暴增 | 高 |
| Distributed Lock | 無法加鎖 → checkout 失去防超賣保護 | 很高 |
| Rate Limiting | 無法限流 → 所有 request 放行 | 中 |

**降級策略（Fallback）：**
- 可用性優先：Redis 掛 → 放行所有 request（電商常見）
- 安全性優先：Redis 掛 → 拒絕所有 request（金融系統）

---

## 踩過的坑

### 1. tryLock 用 set 而唔係 setIfAbsent
```java
// ❌ set() — 任何人都能寫入，鎖無意義
redisTemplate.opsForValue().set(key, "locked", expireSeconds, TimeUnit.SECONDS);

// ✅ setIfAbsent() — 只有第一個人能拿到鎖
redisTemplate.opsForValue().setIfAbsent(key, "locked", expireSeconds, TimeUnit.SECONDS);
```

### 2. Stock validation 放在鎖外面
```java
// ❌ 驗證在鎖外 → 兩個人同時驗證通過
if (product.getStock() < item.getQuantity()) throw ...;
boolean locked = redisService.tryLock(lockKey, 10);

// ✅ 驗證在鎖裡面 → 只有拿到鎖的人才能驗證
boolean locked = redisService.tryLock(lockKey, 10);
try {
    if (product.getStock() < item.getQuantity()) throw ...;
    product.setStock(product.getStock() - item.getQuantity());
} finally {
    redisService.unlock(lockKey);
}
```

### 3. isRateLimited 用 set() 覆蓋 count
```java
// ❌ set() 覆蓋了 INCR 的值
if (count == 1) redisTemplate.opsForValue().set(key, "x", windowSeconds, TimeUnit.SECONDS);

// ✅ expire() 只設 TTL，不改值
if (count == 1) redisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
```

### 4. Rate Limit 返回 429 但忘記 return
```java
// ❌ 設了 429 但繼續執行
if (isLimited) response.setStatus(429);
filterChain.doFilter(request, response);  // 仍然執行！

// ✅ return 停止執行
if (isLimited) {
    response.setStatus(429);
    return;
}
filterChain.doFilter(request, response);
```

### 5. @Configuration vs @Component
- `@Configuration` — 定義 Bean 的配置類
- `@Component` — 通用組件（Filter、Service 等）
- Filter 是組件，用 `@Component`

---

## 面試題自測

1. **「什麼是分布式鎖？為什麼不能用 Java synchronized？」** — synchronized 只鎖一個 JVM 進程，分布式部署有多個 server，需要跨 server 的鎖 → Redis
2. **「Redis 分布式鎖的 expire time 設多長？太長/太短有什麼問題？」** — 太長用戶等；太短業務未完成鎖過期，仍有 race condition
3. **「Rate Limiting 固定窗口 vs 滑動窗口？」** — 固定窗口有邊界突發問題；滑動窗口更精準但實現更複雜
4. **「Filter Chain 的順序為什麼重要？Rate Limit 為什麼放最前？」** — 最便宜的檢查先做，避免浪費資源在惡意請求上
5. **「Redis 掛了系統會怎樣？如何降級？」** — Cache miss + 無鎖 + 無限流；降級策略取決於業務需求
6. **「INCR 為什麼是原子操作？」** — Redis 單線程，所有命令串行執行，不存在兩個 INCR 同時讀到相同值

---

## 下課預告：Lesson 11 — RocketMQ Basics
- 消息隊列概念（為什麼需要異步？）
- Producer / Consumer 模型
- 訂單處理異步化
- 消息可靠性保證
