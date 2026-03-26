# Lesson 2: E-Commerce Database Design

**Date:** 2026-03-26

---

## 核心概念

### Relational DB vs NoSQL（面试必问）

电商系统用Relational DB因为：
1. **数据有强关系** — User↔Order↔Product，foreign key表达自然
2. **ACID transaction** — 扣库存+创建订单+扣余额必须原子性
3. **固定schema** — 商品field稳定，fixed schema防止dirty data

NoSQL适合：product reviews、activity logs、推荐系统等flexible schema场景

面试答法：
> "I'd choose a relational database because e-commerce data has strong relationships. Transaction support is critical for operations like placing an order. The schema is well-defined and stable. For flexible data like product search, we'd add ElasticSearch."

### Price Snapshot（价格快照）

OrderItem必须snapshot当时既商品名同价格，唔系reference Product表。因为商品价格会变，但订单要保留落单当时既数据。

### 点解唔用double/float存钱

```java
double result = 0.1 + 0.2; // = 0.30000000000000004
```

浮点数有精度问题。Safe选择：
- 整数存cents（`$19.99` → `1999L`）
- `BigDecimal`

### JPA vs Hibernate vs Spring Data JPA

```
JPA (Specification)           ← Java官方接口标准
  └── Hibernate (Implementation)  ← 实际implement JPA
      └── Spring Data JPA (Abstraction) ← 简化Hibernate使用
```

类比：JPA = USB标准，Hibernate = USB线，Spring Data JPA = dock station

Spring Data JPA只需定义interface + method naming convention，自动generate SQL：
```java
List<Product> findByCategoryId(Long categoryId); // 唔使写SQL
```

### @Enumerated 永远用 EnumType.STRING

```java
@Enumerated(EnumType.STRING)  // DB存 "BUYER" ✅
@Enumerated                    // DB存 0 (ordinal) ❌ 顺序一改就乱
```

### OneToMany / ManyToOne 关系

- 有 `@JoinColumn` 嗰边 = relationship **owner**（控制FK column）
- `mappedBy` 嗰边 = **inverse side**（只系reference）
- `cascade = CascadeType.ALL` — save parent自动save children

### Category自引用 — Adjacency List Pattern

```java
@ManyToOne
@JoinColumn(name = "parent_id")
private Category parent; // null = 顶级分类
```

缺点：查询所有子分类需要recursive query（MySQL 8+ `WITH RECURSIVE` CTE）

### Lombok — Entity只用呢三个

```java
@Getter @Setter @NoArgsConstructor
```

**唔好用 `@Data`** — 会generate `equals()`/`hashCode()`/`toString()` 基于所有field，互相reference既Entity会无限循环 → StackOverflow

### Validation放边层？

```
Controller (DTO)  ← ✅ input validation (@Email, @NotBlank)
Service           ← ✅ business rules
Entity            ← ❌ 只做DB constraint (@Column nullable/unique)
```

Entity既 `@Column(nullable = false)` 系database constraint，唔系input validation。Lesson 3会加DTO + Bean Validation。

---

## Database Schema

### ER Diagram
```
categories (self-referencing via parent_id)
    │
    │ one-to-many
    ▼
products ──── many-to-one ──── users (seller)
    │
    │ one-to-many
    ▼
order_items ──── many-to-one ──── orders ──── many-to-one ──── users (buyer)
```

### Tables

**users**: id, username, password(BCrypt), email(unique), phone, role(BUYER/SELLER/ADMIN), active, created_at, updated_at

**categories**: id, name, description, parent_id(self-ref), created_at, updated_at

**products**: id, name, description, price(cents), stock, status(ON_SALE/OFF_SHELF), image_url, category_id(FK), seller_id(FK), created_at, updated_at

**orders**: id, user_id(FK), shipping_address(snapshot), tracking_number, order_status(PENDING_PAYMENT/PAID/SHIPPED/DELIVERED/COMPLETED/RETURNED/CANCELLED), total_price(cents), payment_method, payment_time, created_at, updated_at

**order_items**: id, order_id(FK), product_id(FK), product_name(snapshot), product_price(snapshot), quantity, total_price

### Order Status State Machine
```
PENDING_PAYMENT → PAID → SHIPPED → DELIVERED → COMPLETED
                   ↓                              ↓
               CANCELLED                       RETURNED
```

---

## Dependencies Added
- `spring-boot-starter-data-jpa` — ORM abstraction
- `mysql-connector-j` (runtime) — MySQL driver
- `lombok` (optional) — reduce boilerplate

---

## 面试题自测

1. **「点解电商用Relational DB唔用NoSQL？」**
2. **「OrderItem点解要snapshot price同product name？」**
3. **「`@Enumerated` 点解要用STRING唔用ORDINAL？」**
4. **「JPA、Hibernate、Spring Data JPA三者关系？」**
5. **「`mappedBy` 系咩意思？边边系relationship owner？」**
6. **「点解唔用double存钱？」**
7. **「Entity class点解唔好用@Data？」**
8. **「Validation应该加喺边层？Entity定DTO？」**

---

## 下课预告：Lesson 3 — RESTful API Design
- DTO + Bean Validation
- CRUD endpoints for Products
- API response envelope pattern
- Error handling
