# Lesson 13: ElasticSearch Integration

**Date:** 2026-04-06

---

## 核心概念

### 為什麼需要 ElasticSearch？

MySQL `LIKE '%keyword%'` 嘅問題：

| 問題 | 例子 |
|------|------|
| 唔支持分詞 | 搜「蘋果手機」搵唔到「Apple iPhone」 |
| 唔支持模糊匹配 | 搜「iphon」搵唔到「iPhone」 |
| 唔支持相關性排序 | 邊個結果最相關？LIKE 唔知 |
| 性能差 | `%keyword%` 無法用 index，全表掃描 |

### 倒排索引 vs B+ Tree（面試重點）

```
MySQL B+ Tree（正向索引）：
  id → document
  1  → "Apple iPhone 15 Pro"
  2  → "Samsung Galaxy S24"

  問：邊個 document 包含 "apple"？→ 全表掃描 → 慢

ElasticSearch 倒排索引（Inverted Index）：
  term     → document ids
  "apple"  → [1]
  "iphone" → [1]
  "samsung" → [2]
  "galaxy"  → [2]

  問：邊個 document 包含 "apple"？→ 直接查 → [1] → 快
```

### ES 概念同 MySQL 對比

| MySQL | ElasticSearch |
|-------|--------------|
| Database | Index |
| Row | Document |
| Column | Field |
| SQL | Query DSL |
| B+ Tree | Inverted Index |

---

## Docker 部署

```yaml
elasticsearch:
  image: docker.elastic.co/elasticsearch/elasticsearch:8.17.0
  ports:
    - "9201:9200"    # 9201 因為本機 9200 被佔用
  environment:
    - discovery.type=single-node        # 單機模式
    - xpack.security.enabled=false      # 關閉安全驗證（開發用）
    - ES_JAVA_OPTS=-Xms256m -Xmx256m   # 記憶體限制
```

驗證 ES 運行：
```bash
curl http://localhost:9201
# 返回 "You Know, for Search" 代表成功
```

---

## Spring Boot 整合

### Dependency

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-elasticsearch</artifactId>
</dependency>
```

### application.properties

```properties
spring.elasticsearch.uris=http://localhost:9201
```

### 1. ProductDocument（ES Document）

```java
@Document(indexName = "products")
@Data
public class ProductDocument {
    @Id  // org.springframework.data.annotation.Id（唔係 jakarta.persistence.Id！）
    Long id;
    String name;
    String description;
    Long price;
    String categoryName;
    String seller;
}
```

**注意 @Id 嘅 import：**
- `jakarta.persistence.Id` → JPA（MySQL）
- `org.springframework.data.annotation.Id` → Spring Data（ES、Redis）

### 2. ProductSearchRepository

```java
public interface ProductSearchRepository extends ElasticsearchRepository<ProductDocument, Long> {
}
```

同 JpaRepository 一樣，繼承就有 CRUD。`save()`、`deleteById()`、`findById()` 用法完全相同。

### 3. 數據同步（MySQL → ES）

喺 ProductService 嘅 create/update/delete 加同步：

```java
// Create / Update
Product saved = productRepository.save(product);
productSearchRepository.save(toDocument(product));  // 同步去 ES

// Delete
productRepository.delete(product);
productSearchRepository.deleteById(id);             // 從 ES 刪除

// Helper method — 避免重複代碼
private ProductDocument toDocument(Product product) {
    ProductDocument doc = new ProductDocument();
    doc.setId(product.getId());
    doc.setName(product.getName());
    doc.setDescription(product.getDescription());
    doc.setPrice(product.getPrice());
    doc.setCategoryName(product.getCategory().getName());
    doc.setSeller(product.getSeller().getUsername());
    return doc;
}
```

### 4. ProductSearchService（全文搜索）

```java
@Service
public class ProductSearchService {
    private final ElasticsearchOperations elasticsearchOperations;

    public List<ProductDocument> searchProducts(String keyword) {
        Criteria criteria = new Criteria("name").matches(keyword)
                .or(new Criteria("description").matches(keyword));
        Query query = new CriteriaQuery(criteria);
        SearchHits<ProductDocument> hits = elasticsearchOperations.search(query, ProductDocument.class);
        return hits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .collect(Collectors.toList());
    }
}
```

- **`matches`** = 全文搜索（分詞匹配）
- **`or`** = name 或 description 任一匹配
- **`SearchHits`** = 搜索結果（包含分數等資訊）

### 5. Controller

```java
@GetMapping("/search/es")
public List<ProductDocument> searchES(@RequestParam String keyword) {
    return productSearchService.searchProducts(keyword);
}
```

---

## 驗證：ES 搜索 vs MySQL 搜索

| 測試 | MySQL LIKE | ES |
|------|-----------|-----|
| 搜「iPhone」 | ✅ 精確匹配 | ✅ |
| 搜「Apple」 | ✅ | ✅ |
| 搜「apple flagship」 | ❌ 冇精確匹配 | ✅ 分詞成 apple + flagship |
| 搜「chip」 | ❌ 唔喺 name 入面 | ✅ 匹配 description |
| Hibernate SQL | 有 | **無！直接查 ES，DB 零壓力** |

---

## 架構

```
用戶搜索 → ProductSearchService → ElasticSearch（全文搜索，<10ms）
                                    ↑
商品新增/更新/刪除 → ProductService → MySQL（主數據庫）
                                    → ES（同步索引）
                                    → Redis（清 cache）
```

MySQL = 數據源（Source of Truth）
ES = 搜索索引（Read Optimized）
Redis = 熱數據緩存

---

## 面試題自測

1. **「ElasticSearch 的倒排索引和 MySQL B+ Tree 有什麼區別？」** — B+ Tree 適合精確查找和範圍查詢；倒排索引適合全文搜索，將 term 映射到 document
2. **「為什麼不直接用 MySQL 做搜索？」** — LIKE 不支持分詞、模糊匹配、相關性排序，且全表掃描性能差
3. **「ES 和 MySQL 的數據怎麼保持同步？」** — 在寫入 MySQL 時同步寫入 ES（雙寫）。更複雜的方案：用 MQ 異步同步或用 Canal 監聽 binlog
4. **「如果 ES 和 MySQL 數據不一致怎麼辦？」** — MySQL 是 Source of Truth，定期全量同步或用 MQ 保證最終一致性
5. **「@Id 用 jakarta.persistence.Id 還是 spring.data.annotation.Id？」** — JPA entity 用 jakarta，ES/Redis document 用 spring data
6. **「ES 適合做什麼？不適合做什麼？」** — 適合：全文搜索、日誌分析、聚合統計。不適合：事務操作、頻繁更新、關係型查詢

---

## 下課預告：Lesson 14 — Sentinel Traffic Control
- 限流規則動態配置
- Circuit Breaker（熔斷）
- 降級策略
- Sentinel Dashboard
