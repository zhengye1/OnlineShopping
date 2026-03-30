# Lesson 5: Product Module (Advanced)

**Date:** 2026-03-29

---

## 核心概念

### Pagination — 两种方式（面试常问）

**Offset-based（我地用既）：**
```
GET /api/products?page=0&size=10
```
- 简单直观，适合传统分页
- 缺点：page越大越慢（MySQL要skip前面所有rows）

**Cursor-based：**
```
GET /api/products?cursor=lastId&size=10
```
- 用上一页最后一个ID做起点，唔使skip
- 适合infinite scroll、large datasets
- 缺点：唔可以跳页

面试答法：
> "Offset-based is simpler for standard page navigation. Cursor-based avoids the offset scan problem and is better for infinite scroll or very large datasets like social media feeds."

### Spring Data Pagination

```java
// Controller — 接收参数
@RequestParam(defaultValue = "0") int page
@RequestParam(defaultValue = "10") int size

// Service — 创建Pageable
Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());

// Repository — return Page<Entity>
Page<Product> findByStatus(ProductStatus status, Pageable pageable);
```

`Page<T>` 包含：content, totalElements, totalPages, number(当前页), isLast

### Generic PageResponse

```java
public class PageResponse<T> {  // Generic — 可reuse于任何entity
    private List<T> content;
    private int page, size, totalPages;
    private long totalElements;
    private boolean last;
}
```

### Optional Filters Pattern (JPQL)

```sql
AND (:keyword IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%')))
```
Parameter系null就skip呢个条件 — 所有filter都optional。

### JPQL vs SQL（面试常问）
- JPQL query against **entities and fields**（`Product p`, `p.name`）
- SQL query against **tables and columns**（`products`, `name`）
- Hibernate translate JPQL → actual SQL
- Database-agnostic

### `required = false` + Primitive vs Wrapper

```java
@RequestParam(required = false) Long minPrice  // ✅ 可以null
@RequestParam(required = false) long minPrice   // ❌ primitive default 0, 唔系null
```

面试知识点：optional parameters必须用wrapper type。

### SecurityContext — 获取当前用户

```java
String username = SecurityContextHolder.getContext().getAuthentication().getName();
```
从JWT token extract出username，用嚟assign seller等操作。

---

## API Endpoints（Updated）

```
GET    /api/products?page=0&size=10&sortBy=createdAt&direction=desc
         → PageResponse<ProductResponse>（public, only ON_SALE）

GET    /api/products/search?keyword=&categoryId=&minPrice=&maxPrice=&page=0&size=10
         → PageResponse<ProductResponse>（public, all filters optional）

GET    /api/products/{id}     → ProductResponse
POST   /api/products          → ProductResponse（authenticated, auto-assign seller）
PUT    /api/products/{id}     → ProductResponse（authenticated）
DELETE /api/products/{id}     → 204（ADMIN only）
```

---

## 面试题自测

1. **「Product table有100万条record，API点样设计？」** — Pagination
2. **「Offset-based同Cursor-based pagination有咩分别？」**
3. **「JPQL同SQL有咩分别？」**
4. **「optional filter点样implement？」** — `:param IS NULL OR ...` pattern
5. **「点样知道当前登录既用户系边个？」** — SecurityContextHolder
6. **「@RequestParam optional参数点解要用Long唔用long？」**

---

## 下课预告：Lesson 6 — Shopping Cart
- Cart management (add/remove/update quantity)
- Session vs DB storage
- Cart-Product relationship
