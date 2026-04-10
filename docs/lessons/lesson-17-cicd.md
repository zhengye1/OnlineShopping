# Lesson 17: CI/CD Pipeline

## Part A: Unit Testing

### Unit Test 核心概念

- **Unit Test**: 測試單一 class 嘅邏輯，所有 dependency 都 mock 掉
- **Integration Test**: 測試多個 component 一齊運作（用真 DB / Testcontainers — Lesson 18）

### Mockito 基礎

```java
@ExtendWith(MockitoExtension.class)  // 啟用 Mockito
@Mock SomeRepository repo;            // 假嘅 dependency
@InjectMocks SomeService service;     // 被測試嘅 class，自動注入 mock
```

**核心 API:**
| API | 用途 |
|---|---|
| `when(mock.method()).thenReturn(value)` | 控制 mock 嘅 return value |
| `when(mock.method()).thenThrow(exception)` | 控制 mock throw exception |
| `verify(mock, times(1)).method()` | 驗證 method 被 call 幾次 |
| `verify(mock, never()).method()` | 驗證 method 從未被 call |
| `assertEquals(expected, actual)` | 驗證值相等 |
| `assertThrows(Exception.class, () -> ...)` | 驗證有 throw exception |

**Static imports:**
```java
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
```

### @Mock vs @Spy

| | @Mock | @Spy |
|---|---|---|
| 本質 | 完全假嘅 object | 真實 object，可選擇性覆蓋 |
| 未 stub 嘅 method | return null/0/false | 執行真實邏輯 |
| 使用率 | ~90% | ~10% |
| 適用場景 | 隔離 dependency | 需要真實邏輯但改部分行為 |

### Test Naming Convention

```
methodName_condition_expectedResult
```
例：`getProductById_cacheHit_returnsFromCache`

### 常見 Pattern

**1. Mock 兩層 chain (Redis ValueOperations):**
```java
@Mock RedisTemplate<String, Object> redisTemplate;
@Mock ValueOperations<String, Object> valueOperations;

when(redisTemplate.opsForValue()).thenReturn(valueOperations);
when(valueOperations.get(anyString())).thenReturn(cachedValue);
```

**2. Mock SecurityContext:**
```java
@Mock SecurityContext securityContext;
@Mock Authentication authentication;

when(authentication.getName()).thenReturn("buyer1");
when(securityContext.getAuthentication()).thenReturn(authentication);
SecurityContextHolder.setContext(securityContext);
```

**3. Mock JPA findById (Optional):**
```java
// Found
when(repo.findById(anyLong())).thenReturn(Optional.of(entity));
// Not found
when(repo.findById(anyLong())).thenReturn(Optional.empty());
```

**4. Mock save (service 內部 new 嘅 object):**
```java
// 用 any() match，因為 service 內部 new 嘅 object 同你準備嘅唔係同一個
when(repo.save(any(Product.class))).thenReturn(mockProduct);
```

### 常見坑位

1. **`anyLong()` 只可用喺 `when()` 入面** — 真正 call service 用具體值 `1L`
2. **`findById` return `Optional`** — 唔係直接 return entity
3. **`verify(mock, never()).method()`** — 唔係 `verify(mock.method(), never())`
4. **Mock chain 要逐層 mock** — `redisTemplate.opsForValue()` 先，再 `valueOperations.get()`
5. **`toResponse()` 會 access 關聯 entity** — mockProduct 要 set category / seller，唔係就 NPE

### Test Cases 總覽

| Test Class | Method | Tests |
|---|---|---|
| ProductServiceTest | getProductById | cache hit, cache miss, not found |
| ProductServiceTest | createProduct | success (SecurityContext + save + ES) |
| CartServiceTest | addToCart | success new item, product not on sale |
| OrderServiceTest | cancelOrder | success + stock restore, wrong status |

---

## Part B: GitHub Actions CI

### Workflow 三要素

1. **When** — 咩 event trigger (`push`, `pull_request`)
2. **Where** — 邊個環境 run (`ubuntu-latest` + Java 21)
3. **What** — 做啲咩 (checkout → setup JDK → `mvn test`)

### ci.yml

```yaml
name: Continuous Integration
on:
  push:
    branches: [main]
  pull_request:
    branches: [main]
jobs:
  tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '21'
          cache: 'maven'
      - run: mvn test
```

**Key points:**
- `pull_request` trigger 最重要 — merge 之前就知道 code 有冇問題
- `cache: 'maven'` — cache dependencies，第二次 run 開始快好多
- `mvn test` = compile + test，唔需要 `mvn package`（CI 唔使打包 JAR）

### Test Environment Config

`src/test/resources/application.properties` 會**自動覆蓋** main 嗰個。

```properties
# H2 in-memory DB 代替 MySQL
spring.datasource.url=jdbc:h2:mem:testdb
spring.datasource.driver-class-name=org.h2.Driver
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.jpa.hibernate.ddl-auto=create-drop

# Disable 外部服務
spring.autoconfigure.exclude=\
  RedisAutoConfiguration, ElasticsearchDataAutoConfiguration, ...

# RocketMQ 假地址
rocketmq.name-server=localhost:19876

# Sentinel 禁用
spring.cloud.sentinel.enabled=false
```

**H2 Database:**
- In-memory DB，JVM 啟動就有，唔需要裝
- `create-drop` = 每次 test 建表 + 結束刪除
- `<scope>test</scope>` 確保 production 唔會包

---

## Interview Drills

<details>
<summary>Q1: Unit test 同 integration test 有咩分別？咩時候用邊個？</summary>

- **Unit test**: 測試單一 class，所有 dependency mock 掉。速度快（毫秒級），唔需要外部服務。用嚟驗證 business logic。
- **Integration test**: 測試多個 component 一齊，用真實（或 container 化）嘅 DB/cache。速度慢（秒級），需要啟動 Spring context。用嚟驗證 component 之間嘅 interaction。
- CI pipeline 應該兩種都跑，unit test 做 fast feedback，integration test 做 confidence gate。
</details>

<details>
<summary>Q2: 點解 CI 要喺 PR 而唔係 merge 之後先跑？</summary>

喺 PR 跑 CI = merge 之前就知道 code 有冇問題（shift left）。如果 merge 完先跑，fail 咗就會 break main branch，影響所有人。PR CI 係一個 quality gate — test 唔 pass 就唔畀 merge。
</details>

<details>
<summary>Q3: @Mock 同 @Spy 有咩分別？咩時候用 @Spy？</summary>

- **@Mock**: 完全假嘅 object，所有 method return null/0/false，要 `when().thenReturn()` setup
- **@Spy**: 包住真實 object，未 stub 嘅 method 行真實邏輯，只覆蓋你指定嘅 method
- 90% 情況用 @Mock。@Spy 用喺你需要真實邏輯但想改部分行為，例如 spy 一個 service 嘅大部分 method 係真嘅但 mock 掉其中一個 external call
</details>

<details>
<summary>Q4: CI 環境冇 MySQL/Redis，點樣跑 test？</summary>

兩個策略：
1. **H2 in-memory DB** — 代替 MySQL，JVM 內建，唔需要外部服務。用 `spring.autoconfigure.exclude` disable Redis/ES 等。適合 unit test。
2. **Testcontainers** — 用 Docker 啟動真實 MySQL/Redis container。適合 integration test，確保同 production 行為一致。
</details>

<details>
<summary>Q5: `when(repo.save(any(Product.class)))` 點解要用 `any()` 而唔係具體 object？</summary>

因為 service 內部會 `new Product()` 再 `save(product)`，呢個 product 係 service 自己 create 嘅，同你 test 入面準備嘅 mockProduct 係**唔同嘅 instance**。用 `any(Product.class)` 先可以 match 到任何 Product 參數。如果用具體 object，Mockito 會用 `equals()` 比較，match 唔到就 return null → NPE。
</details>
