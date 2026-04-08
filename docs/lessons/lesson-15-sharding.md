# Lesson 15: Database Sharding 數據庫分片

**日期**: 2026-04-08
**老師**: Lyon
**學生**: 小V

---

## 📖 今日學咩

資料庫去到單機頂唔順嘅時候，點樣橫向擴展？呢課由理論到實戰，完整走一次 **database sharding**。

- 點解要 sharding（單機 MySQL 嘅物理極限）
- Horizontal vs Vertical sharding
- Sharding 四大挑戰
- Shard key 點揀
- Snowflake ID / Consistent Hashing / CQRS 輔助方案
- 實戰：用 **ShardingSphere-JDBC** 整合兩個 MySQL shard

---

## 1. 點解需要 Sharding？

### 單機 MySQL 嘅物理極限

| 維度 | 極限 |
|---|---|
| 單表行數 | ~1000 萬 – 2000 萬（B+Tree 高度開始跳 4） |
| 單庫大小 | ~1 TB 左右開始難頂 |
| QPS / 連接數 | 硬件 + connection pool 瓶頸 |
| 備份時間 | 數據大到 backup 都要好幾個鐘 |

**關鍵問題**：加硬件（scale up）有天花板，而且貴。→ 要 **scale out**，將數據拆到多個 MySQL 實例。

### 點解唔係單純加 index / cache？

- **Index**: 只能加快查詢，**解決唔到寫入 / 存儲 / 連接數瓶頸**
- **Cache**: 只幫 read，寫入仍然打落 DB；而且冷數據 cache 唔晒

Sharding 解決嘅係**寫入 + 存儲 + 連接**嘅根本擴展問題。

---

## 2. Replication vs Sharding

| | Replication（複製） | Sharding（分片） |
|---|---|---|
| **數據** | 每個 node **完整 copy** | 每個 node 只有**一部分** |
| **解決** | 讀擴展、高可用 | 寫擴展、容量擴展 |
| **難度** | 相對簡單 | 複雜（routing / 跨片查詢） |

現實生產通常**兩者都用**：每個 shard 內部做 master-slave replication。

---

## 3. Horizontal vs Vertical Sharding

### Vertical Sharding（垂直拆分）
按**表** / **列**劃分。
- **按業務拆庫**：user_db / order_db / product_db（對應微服務）
- **按列拆表**：熱數據 vs 冷數據分開（例如 user 表嘅 bio/avatar 分離）

### Horizontal Sharding（水平拆分）✅ 今日主角
**同一個表**按行劃分到多個 shard，每個 shard 結構一樣，只係存唔同行。

```
orders table (原本單表 1 億行)
  ↓ shard by user_id % 2
shard0.orders (5000 萬行，user_id 係雙數)
shard1.orders (5000 萬行，user_id 係單數)
```

---

## 4. Sharding 四大挑戰 ⚠️

### ① Query Routing（查詢路由）
> 查 `user_id=123` 要去邊個 shard？

需要一個 routing 組件（client-side 或 proxy-side）根據 shard key 計算目標 shard。

### ② Cross-Shard Query（跨片查詢）
> `SELECT * FROM orders WHERE amount > 1000` 冇帶 user_id 點算？

必須**廣播到所有 shard** → 每個 shard 執行 → 應用層 merge / sort。慢且成本高。
**對策**：盡量用帶 shard key 嘅查詢，或者做 CQRS 建另一份異構存儲（例如 ES）。

### ③ Cross-Shard Transaction（跨片事務）
> 用戶 A（shard0）轉帳比 用戶 B（shard1），一個 DB transaction 搞唔掂。

單機 ACID 失效，要用**分佈式事務**（Lesson 16 會講：Saga / TCC / 最終一致性）。

### ④ Resharding（擴容 / 重分片）
> 原本 4 個 shard 變 8 個，舊數據點搬？

`user_id % 4` → `user_id % 8`：**幾乎所有數據**都要搬（mod 改變）。
**對策**：
- **Consistent Hashing**（一致性哈希）：只搬一小部分
- **Virtual Buckets**（虛擬桶）：物理 shard 數可調，邏輯桶數固定

---

## 5. Shard Key 點揀？🔑

**兩大原則**（按重要性排）：

### 原則一：Match Query Pattern（配合查詢模式）⭐ 最重要
> Shard key 應該係**最常用嘅 WHERE 條件**

如果 `orders` 99% 係按 user 查（「我嘅訂單」），就用 `user_id`。
如果 90% 係按 seller 查，就用 `seller_id`。
**有唔同 query pattern？→ CQRS：一份按 user shard，另一份異構存儲按 seller 組織**。

### 原則二：Distribution Evenness（分佈均勻）
避免熱點 shard。`user_id` 一般 OK；**日期**會造成熱點（今日所有新 order 擠落同一 shard）。

### ❌ 反例
- **Auto-increment ID as shard key**: 最新數據全部擠落最後一個 shard（熱點）
- **Email** （用 email domain 分）: Gmail 用戶佔 70%，嚴重不均

### 💡 Snowflake ID（雪花 ID）
Twitter 開源嘅 64-bit 分佈式 ID 生成方案：

```
| 1 bit 符號位 | 41 bits 時間戳 | 10 bits 機器 ID | 12 bits 序列號 |
```

- **全局唯一**：多機器同時生成都唔會撞
- **趨勢遞增**：對 B+Tree 索引友好
- **高性能**：本地生成，唔使查 DB
- **可做 shard key**：但要注意前面嘅時間戳會導致熱點，通常會 reshuffle 高位 bit

---

## 6. Sharding Middleware 選型

| 方案 | 類型 | 優點 | 缺點 |
|---|---|---|---|
| **ShardingSphere-JDBC** | Client-side | 無額外進程、性能好 | 只支持 Java |
| **ShardingSphere-Proxy** | Proxy | 語言無關、運維統一 | 多一跳、proxy 係瓶頸 |
| **MyCat** | Proxy | 成熟 | 活躍度下降 |
| **Vitess** | Proxy | YouTube 在用、K8s 友好 | 學習曲線陡 |

今日我哋用 **ShardingSphere-JDBC**（client-side）：Spring Boot 啟動後，`DataSource` 被 ShardingSphere wrap，SQL 喺應用層被 parse → route → rewrite → execute → merge。

---

## 7. 🛠️ 實戰：整合 ShardingSphere-JDBC

### 7.1 Docker 起兩個 MySQL Shard

`docker-compose.yml` 加：

```yaml
mysql-shard0:
  image: mysql:8.0
  container_name: mysql-shard0
  ports:
    - "3307:3306"
  environment:
    MYSQL_ROOT_PASSWORD: root
    MYSQL_DATABASE: online_shopping_shard0
  volumes:
    - mysql-shard0-data:/var/lib/mysql

mysql-shard1:
  image: mysql:8.0
  container_name: mysql-shard1
  ports:
    - "3308:3306"
  environment:
    MYSQL_ROOT_PASSWORD: root
    MYSQL_DATABASE: online_shopping_shard1
  volumes:
    - mysql-shard1-data:/var/lib/mysql

volumes:
  mysql-shard0-data:
  mysql-shard1-data:
```

### 7.2 pom.xml 加 ShardingSphere

```xml
<dependency>
    <groupId>org.apache.shardingsphere</groupId>
    <artifactId>shardingsphere-jdbc</artifactId>
    <version>5.5.1</version>
</dependency>
```

⚠️ **版本血淚史**:
- **5.5.3** → `SPI-00001: ShardingSphereModeConfigurationURLLoader` 錯誤
- **5.4.1** → artifact 名唔同（係 `shardingsphere-jdbc-core`），再加 JAXB-API 喺 Java 21 被移除
- **5.5.1** ✅ → 最終 work

### 7.3 application.properties

```properties
# 原本：
# spring.datasource.url=jdbc:mysql://localhost:3306/online_shopping
# spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver

# 改成 ShardingSphere driver：
spring.datasource.driver-class-name=org.apache.shardingsphere.driver.ShardingSphereDriver
spring.datasource.url=jdbc:shardingsphere:classpath:sharding-config.yaml
```

### 7.4 sharding-config.yaml（核心）

```yaml
mode:
  type: Standalone
  repository:
    type: JDBC

dataSources:
  shard0:
    dataSourceClassName: com.zaxxer.hikari.HikariDataSource
    driverClassName: com.mysql.cj.jdbc.Driver
    jdbcUrl: jdbc:mysql://localhost:3307/online_shopping_shard0?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true
    username: root
    password: root
  shard1:
    dataSourceClassName: com.zaxxer.hikari.HikariDataSource
    driverClassName: com.mysql.cj.jdbc.Driver
    jdbcUrl: jdbc:mysql://localhost:3308/online_shopping_shard1?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true
    username: root
    password: root

rules:
  - !SHARDING
    tables:
      orders:
        actualDataNodes: shard${0..1}.orders
        tableStrategy:
          none:
        databaseStrategy:
          standard:
            shardingColumn: user_id
            shardingAlgorithmName: user_id_mod
    shardingAlgorithms:
      user_id_mod:
        type: INLINE
        props:
          algorithm-expression: shard$->{user_id % 2}
  - !SINGLE
    tables:
      - "*.*.*"

props:
  sql-show: true
```

**要點**：
- `orders` 表按 `user_id % 2` 分去 shard0 / shard1
- 其他表（users, products...）用 `!SINGLE` rule 當普通單表處理
- `sql-show: true` 會打印 **Logic SQL** 同 **Actual SQL**，用嚟調試

⚠️ **Algorithm 選擇血淚**:
- 一開始用 `type: MOD`（auto sharding algorithm）→ 要 `autoTables` 配置，同我哋用嘅 `tables` + `databaseStrategy` 格式唔兼容
- 改用 `type: INLINE` + `shard$->{user_id % 2}` ✅

### 7.5 啟動驗證

啟動 Spring Boot 後，console 會見到：

```
Logic SQL: insert into orders ...
Actual SQL: shard1 ::: insert into orders ...
```

**「Logic SQL」= 應用層寫嘅 SQL**
**「Actual SQL」= ShardingSphere route 之後真正跑嘅 SQL，前面會標明落咗邊個 shard**

見到呢行，證明 sharding routing 生效 ✅

---

## 8. 💡 生產環境要考慮嘅嘢（未 demo）

今日嘅 demo 係最簡單形式。生產環境仲要：

1. **每個 shard 內部要做 replication**（master-slave，做備份 + 讀分離）
2. **配置中心**：sharding rule 寫死 YAML，生產用 Nacos / Apollo 等動態配置
3. **Resharding 方案**：Consistent Hashing 或者 virtual buckets 預先規劃
4. **跨片查詢監控**：慢查詢經常係「漏咗 shard key」導致廣播
5. **單表 routing**：`!SINGLE` rule 可以加 `defaultDataSource: shard0` 避免分散

---

## 9. 🎤 Interview Drills

### Q1: 單表 2000 萬行先考慮 sharding，點解？
B+Tree 索引喺 2000 萬行左右高度通常係 3-4，再大就要跳去 4-5 層，每層多一次磁盤 IO。加埋 buffer pool 命中率下降，查詢 latency 開始明顯增加。呢個係經驗數字，唔係硬規則。

### Q2: Shard key 改變會點？
**極度痛苦**。因為所有舊數據都要重新 route + 搬。正確做法：上線前就要想清楚 query pattern，或者用 CQRS 建多份異構存儲。

### Q3: Sharding 點解唔解決 JOIN？
跨片 JOIN 要將兩邊數據都 pull 返應用層 merge，效能差。對策：
- **Binding tables**：相關表（例如 orders + order_items）用同一個 shard key，保證同一 user 嘅數據喺同一 shard
- **Broadcast tables**：細表（例如 categories）每個 shard 都 copy 一份
- **應用層 join**：分別查再 merge
- **CQRS**：建異構存儲預先 join 好

### Q4: ShardingSphere-JDBC 同 Proxy 點揀？
- **JDBC 模式**：應用直連 DB，性能好、架構簡單、但只支持 Java
- **Proxy 模式**：多語言支持、運維統一、但多一跳網絡 + proxy 本身要 HA
- **實際**：唔少公司兩種都用——Java 應用用 JDBC，其他語言 / 工具（BI / DBA）用 Proxy

### Q5: 點解 `user_id % N` 係反面教材但我哋今日仲用？
因為 demo 簡單。生產問題：擴容時 N 變就要搬幾乎所有數據。生產用 consistent hashing 或 virtual buckets。

---

## 10. 🧠 核心 Takeaway

> **Sharding 解決嘅係 scaling 問題，代價係放棄單機 ACID 同簡單 query。**
> **揀 shard key 嘅黃金法則：跟住最常用嘅 query pattern 走。**
> **Middleware 只係工具，真正難嘅係 schema 設計同 query 設計。**

---

## 下一課預告

**Lesson 16: Distributed Transactions** — 跨片事務點解決？Saga / TCC / 本地消息表 / 最終一致性，對應返我哋今日遺留嘅「跨片事務」挑戰。
