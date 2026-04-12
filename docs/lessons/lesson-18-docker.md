# Lesson 18: Docker Containerization

## Part A: Dockerfile (Multi-Stage Build)

### Docker 核心概念

- **Image**: 打包好嘅 snapshot（App + dependencies），唔可變
- **Container**: Image 嘅運行實例（process），可以有多個
- **Dockerfile**: 定義點樣 build image 嘅 script

### Container vs VM

| | Container | VM |
|---|---|---|
| 隔離方式 | 共享 host OS kernel | 獨立 Guest OS |
| 大小 | MB 級 | GB 級 |
| 啟動速度 | 秒級 | 分鐘級 |
| 資源消耗 | 少 | 多 |

### Multi-Stage Build

```dockerfile
# Stage 1: Build (Maven + JDK)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN mvn package -DskipTests

# Stage 2: Run (JRE only)
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
```

**Why multi-stage?**
- **Reproducibility** — 任何人 clone 就可以 `docker build`，唔使裝 Maven/JDK
- **CI/CD 友好** — Pipeline 只需要 Docker
- **Image 更細** — Final image 只有 JRE（~200MB），唔包 Maven + source code

**Key points:**
- `WORKDIR` — 設定工作目錄，避免 file 散落 root `/`
- `COPY --from=build` — 從 Stage 1 複製 artifact 到 Stage 2
- `*.jar` wildcard — 唔使 hardcode version number
- `EXPOSE` — 文檔作用，唔會真正開 port（`docker run -p` 先會）

---

## Part B: Docker Compose

### docker-compose.dev.yml

一個 file 定義所有 services，`docker compose -f docker-compose.dev.yml up` 一行搞掂：

| Service | Image | Port | 用途 |
|---|---|---|---|
| mysql | mysql:8.0 | 3306 | 主 database |
| redis | redis:7 | 6379 | Cache + 分散式鎖 |
| namesrv | apache/rocketmq:5.3.1 | 9876 | MQ NameServer |
| broker | apache/rocketmq:5.3.1 | 10911 | MQ Broker |
| elasticsearch | elasticsearch:8.17.0 | 9200 | 全文搜索 |
| app | build: . | 8080 | Spring Boot App |

**Key points:**
- `depends_on` — 定義啟動順序（app depends on mysql, redis, etc.）
- `volumes` — 持久化 MySQL data，`docker compose down` 唔會丟資料
- `build: .` — 用 project root 嘅 Dockerfile build app image
- Service name = hostname — container 之間用 service name 溝通（`mysql:3306` 唔係 `localhost:3306`）
- `environment` — 覆蓋 Spring Boot config，指向 Docker 內部 hostname

**ES 開發環境 config:**
```yaml
environment:
  - discovery.type=single-node    # 單機模式
  - xpack.security.enabled=false  # 關 HTTPS + auth
  - "ES_JAVA_OPTS=-Xms256m -Xmx256m"  # 限制 memory
```

**Image naming:**
- `image:tag` 用 `:` — e.g. `mysql:8.0`, `redis:7`
- `org/image` 用 `/` — e.g. `apache/rocketmq:5.3.1`

---

## Part C: Testcontainers

### 概念

Testcontainers 喺 test 入面自動起 Docker container（真 MySQL），跑完自動銷毀。

| | docker-compose MySQL | Testcontainers MySQL |
|---|---|---|
| 生命週期 | 手動 `up` / `down` | Test 自動起、自動銷毀 |
| Port | 你指定（3306） | Random（唔會 conflict） |
| 資料 | 持久化（volumes） | 每次全新 |
| 用途 | 日常開發 | Integration test |

### 使用方法

```java
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ProductRepositoryIntegrationTest {

    @Container  // 自動起 MySQL container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("test_db")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource  // 動態注入 random port
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MySQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }
}
```

**Key annotations:**
| Annotation | 用途 |
|---|---|
| `@DataJpaTest` | 只起 JPA 相關 bean（唔起 Service/Controller/Filter） |
| `@Testcontainers` | 啟用 Testcontainers lifecycle |
| `@Container` | 標記要自動管理嘅 container |
| `@AutoConfigureTestDatabase(replace = NONE)` | 唔好用 H2 替代，用我哋嘅 Testcontainers MySQL |
| `@DynamicPropertySource` | 動態注入 container 嘅 random port 到 Spring config |

### 踩過嘅坑

1. **H2 driver vs MySQL URL** — `src/test/resources/application.properties` 設咗 H2 driver，會同 Testcontainers 嘅 MySQL URL conflict。要用 `@DynamicPropertySource` 覆蓋 driver。

2. **`@EnableElasticsearchRepositories` 寫喺 Application class** — `@DataJpaTest` 都會 scan 到，導致 `elasticsearchTemplate` bean 搵唔到。解決：搬去獨立 Config class。

3. **NOT NULL column** — Integration test 用真 MySQL，schema constraint 會真正 enforce。Entity 嘅 required field 一定要 set（e.g. `User.password`）。

4. **Auto-generated ID** — 唔好 hardcode `findById(1L)`，用 `save()` return 嘅 entity 拎 ID。

### Config 職責分離（重構）

**Before（全部堆喺 Application class）：**
```java
@SpringBootApplication
@EnableJpaRepositories(...)
@EnableElasticsearchRepositories(...)  // 影響 test 隔離
@EnableRedisRepositories(...)          // 影響 test 隔離
```

**After（各自獨立）：**
```java
// OnlineShoppingApplication.java
@SpringBootApplication
@EnableJpaRepositories(basePackages = "com.onlineshopping.repository")

// ElasticsearchConfig.java
@Configuration
@EnableElasticsearchRepositories(basePackages = "com.onlineshopping.repository.es")

// RedisConfig.java — 加入現有 config
@Configuration
@EnableRedisRepositories(basePackages = "com.onlineshopping.repository.redis")
```

好處：`@DataJpaTest` 唔會 load ES/Redis config，test 簡單好多。

---

## Interview Drills

<details>
<summary>Q1: Multi-stage build 有咩好處？點解唔用 single-stage？</summary>

Multi-stage build 將 build 同 runtime 分開。Stage 1 用 Maven image（幾百 MB）build JAR，Stage 2 只用 JRE image（~200MB）run。Final image 唔包 source code、Maven、build tools，所以更細、更安全。Single-stage 會將所有嘢留喺 final image，浪費空間同增加 attack surface。
</details>

<details>
<summary>Q2: Docker container 同 VM 有咩分別？</summary>

Container 共享 host OS kernel，只隔離 process；VM 有獨立 Guest OS。Container 更輕量（MB vs GB）、啟動更快（秒 vs 分鐘）、資源消耗更少。但 VM 隔離性更強（完整 OS 層面）。Container 適合 microservice 部署，VM 適合需要完全隔離嘅場景（e.g. 唔同 OS）。
</details>

<details>
<summary>Q3: docker-compose 入面 service name 有咩特別作用？</summary>

Docker Compose 自動建一個 network，service name 就係 hostname。Container 之間用 service name 溝通（e.g. `mysql:3306`），唔使知道實際 IP。呢個就係 service discovery 嘅最簡單形式。`depends_on` 控制啟動順序但唔保證 service ready（需要 healthcheck 或 wait-for script）。
</details>

<details>
<summary>Q4: Testcontainers 同 H2 in-memory DB 有咩分別？咩時候用邊個？</summary>

H2 係 in-memory DB，速度快但同 MySQL 有 SQL dialect 差異（e.g. 某啲 MySQL-specific 語法 H2 唔支援）。Testcontainers 起真 MySQL container，行為同 production 100% 一致但較慢。Unit test 用 H2（快、夠用），Integration test 用 Testcontainers（確保同 production 行為一致）。
</details>

<details>
<summary>Q5: 點解要將 @EnableElasticsearchRepositories 搬出 Application class？</summary>

因為 `@DataJpaTest` 會 scan Application class 上嘅所有 annotation。如果 `@EnableElasticsearchRepositories` 寫喺度，test 環境冇 ES connection 就會 fail。搬去獨立 Config class 後，`@DataJpaTest` 只 load JPA 相關 bean，唔會觸發 ES/Redis 初始化。呢個係 config 職責分離嘅好處 — production 冇影響，test 更容易隔離。
</details>
