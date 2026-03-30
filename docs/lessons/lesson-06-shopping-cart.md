# Lesson 6: Shopping Cart

**Date:** 2026-03-30

---

## 核心概念

### Cart Storage — 三种方案（面试常问）

| | Session/Cookie | Database | Redis |
|---|---|---|---|
| 持久性 | ❌ 关browser就冇 | ✅ 永久 | ✅ 可设过期 |
| 跨设备 | ❌ | ✅ | ✅ |
| 性能 | ✅ 在memory | ❌ DB读写 | ✅ 内存读写 |
| 未登录 | ✅ | ❌ | ✅ |

Production做法（两层结合）：
> "localStorage for guest users, database for authenticated users, Redis as cache layer. Database is source of truth."

### Cart vs Order — 价格处理

- **Cart** — 显示当前价格（从Product读最新），因为只系暂存
- **Order** — snapshot价格（落单当时），因为系交易记录

### 一张表 vs 两张表

唔需要独立Cart表 — CartItem直接关联user_id就够。
> "A separate Cart table is only needed if carts have their own metadata like shared carts or wishlists."

### Composite Unique Constraint

```java
@UniqueConstraint(columnNames = {"user_id", "product_id"})
```
Database层面保证同一user唔会有两条同product record。同一商品再加就update quantity。

### `@Transactional`

Custom delete method（如 `deleteByUserId`）需要在transaction中执行。
`@Transactional` 确保要么全部成功要么全部rollback。

### `@PathVariable` vs `@RequestParam`（面试常问）

```
DELETE /api/cart/123        ← @PathVariable: identifies resource
PUT /api/cart/123?quantity=5 ← @PathVariable + @RequestParam: resource + modifier
```
> "PathVariable for resource identifiers, RequestParam for filters/options."

### Mapping Conflict

同一个HTTP method + 同一个path = conflict。需要用path区分：
```java
@DeleteMapping("/{productId}")  // 移除单件
@DeleteMapping                   // 清空全部
```

---

## Business Rules

1. 只可以加ON_SALE商品入cart
2. 唔可以超过库存数量
3. 同一商品再加 → update quantity（唔系新增record）
4. quantity设为0或以下 → 自动移除

---

## API Endpoints

```
GET    /api/cart                      → CartResponse (需login)
POST   /api/cart                      → CartResponse (需login, 201)
PUT    /api/cart/{productId}?quantity= → CartResponse (需login)
DELETE /api/cart/{productId}           → 204 (需login)
DELETE /api/cart                       → 204 (需login, 清空)
```

---

## 面试题自测

1. **「Shopping cart数据存喺边度？Session、DB定Redis？trade-off？」**
2. **「Cart显示当前价格定snapshot价格？点解？」**
3. **「同一商品加入cart两次应该点处理？」** — update quantity
4. **「`@Transactional` 咩时候要用？」**
5. **「`@PathVariable` 同 `@RequestParam` 有咩分别？」**
6. **「`@UniqueConstraint` 做咩用？」**

---

## 下课预告：Lesson 7 — Order System
- Checkout flow (cart → order)
- Order status management
- Stock deduction
- Tax calculation (Ontario HST 13%)
