# Lesson 9: Redis Cache

**Date:** 2026-04-02

---

## 核心概念

### 为什么需要Cache？

```
无Cache: Client → Backend → DB（每次都query，DB成为bottleneck）
有Cache: Client → Backend → Redis（hit！直接返回，<1ms）
                                 （miss → DB → 存入Redis → 返回）
```

数据库读取可能几十ms，Redis在memory读取通常<1ms。当用户量大时差距显著。

### 什么数据适合Cache？

| 适合 | 不适合 |
|------|--------|
| 热门商品列表（读多写少） | 库存数量（频繁变动，cache导致超卖） |
| 商品详情（多人睇同一个） | Order数据（每个user唔同） |
| Category列表（几乎不变） | Payment状态（实时性要求高） |

> 原则：**读多写少 + 容忍短暂过期** = 适合cache

---

## Cache Invalidation策略（面试重点）

### 1. TTL（Time To Live）
设过期时间，自动删除，下次读重新从DB加载。
```java
redisTemplate.opsForValue().set(key, value, 30, TimeUnit.MINUTES);
```

### 2. Write-Through（写穿透）
更新DB时**同时更新cache**。保证一致性但写操作较慢。

### 3. Cache-Aside（旁路缓存）— 最常用
读时check cache → miss就查DB → 写入cache。写时**删除cache**（唔系更新）。

```java
// 读
Object cached = redisTemplate.opsForValue().get(key);
if (cached != null) return (ProductResponse) cached;
// miss → 查DB → 写cache

// 写（update/delete时）
redisTemplate.delete("product:" + id);
```

### 4. Write-Behind（写后）
写cache就return，异步写DB。最快但有数据丢失风险。

> 面试答法："I'd use Cache-Aside with TTL as safety net. On write, delete the cache entry. On read, check cache first — if miss, load from DB and populate cache with a TTL."

### 单个 vs 列表 Cache策略

| | 单个Product | Product列表 |
|---|---|---|
| 策略 | Cache-Aside（写时删除） | 短TTL（自然过期） |
| 原因 | 知道边个变咗，精准delete `product:1` | 任何product变动都影响list，key组合太多无法逐个delete |
| TTL | 30分钟 | 5分钟 |

---

## Redis数据结构

```java
// String（最基本，我地用呢个）
redisTemplate.opsForValue().set(key, value);
redisTemplate.opsForValue().get(key);

// List
redisTemplate.opsForList()

// Hash
redisTemplate.opsForHash()

// Set
redisTemplate.opsForSet()
```

---

## RedisTemplate配置

```java
@Bean
public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
    RedisTemplate<String, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(connectionFactory);

    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());          // 支持LocalDateTime
    mapper.activateDefaultTyping(...);                    // 存type信息

    template.setKeySerializer(new StringRedisSerializer());              // key → string
    template.setValueSerializer(new GenericJackson2JsonRedisSerializer(mapper));  // value → JSON
    return template;
}
```

### Serializer选择

| | JdkSerializationRedisSerializer（默认） | GenericJackson2JsonRedisSerializer |
|---|---|---|
| 格式 | Binary | JSON |
| 可读性 | ❌ Redis CLI看到乱码 | ✅ 可读JSON |
| 兼容性 | Java版本敏感 | 通用 |
| Type信息 | 自动 | 需要 `activateDefaultTyping` |

### Jackson2Json vs GenericJackson2Json

| | Jackson2JsonRedisSerializer | GenericJackson2JsonRedisSerializer |
|---|---|---|
| 指定class | ✅ 需要 `new ...(Product.class)` | ❌ 自动处理 |
| JSON存type | ❌ | ✅ 加 `@class` field |
| 一个template存多种type | ❌ | ✅ |

---

## 踩过的坑 ⚠️

### 1. LocalDateTime序列化失败 → 500
**现象：** 存入Redis成功，但GET时500 Internal Server Error
**原因：** Jackson默认唔识serialize Java 8 date/time类型
**Fix：** ObjectMapper注册JavaTimeModule
```java
mapper.registerModule(new JavaTimeModule());
```
同Lesson 4 SecurityExceptionHandler果个timestamp array问题同一个根源。

### 2. ImmutableList反序列化失败 → 500
**现象：** 第一次GET成功（从DB读），第二次GET 500（从Redis读）
**原因：** `.toList()` 返回 `ImmutableCollections$ListN`，Jackson唔识deserialize呢个内部class
**Fix：** 用 `.collect(Collectors.toList())` 确保系ArrayList
```java
// ❌ .toList() → ImmutableList → Jackson无法deserialize
// ✅ .collect(Collectors.toList()) → ArrayList → OK
```

### 3. 无NoArgsConstructor → 反序列化失败
**现象：** Redis读出来既JSON无法转返Java object
**原因：** Jackson需要无参构造器创建object，`PageResponse` 只有 `@AllArgsConstructor`
**Fix：** 加 `@NoArgsConstructor`
```java
@Data
@AllArgsConstructor
@NoArgsConstructor  // Jackson deserialization需要
public class PageResponse<T> { }
```

### 4. Unchecked cast warning
**现象：** `(PageResponse<ProductResponse>) obj` 编译warning
**原因：** Java type erasure — runtime时generic type被擦除，无法验证
**Fix：** 加 `@SuppressWarnings("unchecked")`，呢个系Java generics限制，唔系code问题

### 5. Cache Entity vs DTO
**原则：** 永远cache DTO，唔好cache Entity
- Entity有JPA proxy、lazy loading — serialize时可能触发额外query或circular reference
- DTO系纯data object — serialize/deserialize无问题
- Cache DTO = 省DB query + 省转换，两样都省

### 6. Cold Start
**现象：** 第一次从Redis读取较慢（几百ms）
**原因：** Redis connection pool初始化（warming up）
**解法：** 正常现象，后续请求会reuse connection，<1ms

---

## application.properties

```properties
spring.data.redis.host=localhost
spring.data.redis.port=6379
```

---

## 面试题自测

1. **「什么是Cache-Aside pattern？」** — 读时check cache，miss查DB写cache。写时删cache。
2. **「Cache-Aside vs Write-Through vs Write-Behind区别？」**
3. **「什么数据适合cache？什么不适合？」** — 读多写少+容忍过期 vs 实时性高+频繁变动
4. **「单个资源cache同列表cache策略有什么不同？为什么？」** — 单个用Cache-Aside精准删除，列表用短TTL自然过期
5. **「Redis序列化用什么方案？为什么唔用默认？」** — Jackson JSON，可读+通用+跨版本兼容
6. **「.toList() 同 .collect(Collectors.toList()) 有什么区别？」** — immutable vs mutable，影响序列化

---

## 下课预告：Lesson 10 — Redis Advanced
- Redis数据结构深入（Hash, Set, Sorted Set）
- 分布式锁（Distributed Lock）
- 库存扣减防超卖
- Rate Limiting
