# Lesson 1: Project Setup & Spring Boot Fundamentals

**Date:** 2026-03-25

---

## 核心概念

### Spring Boot vs Spring Framework
- Spring Framework 需要大量手动配置（XML config、Tomcat配置、web.xml等）
- Spring Boot 核心理念：**Convention over Configuration（约定大于配置）**
  - 内嵌 Tomcat，唔使自己配server
  - `@RestController` 取代 web.xml + HttpServlet
  - `application.properties` 取代手动 DataSource 配置
  - starter parent 管理所有dependency版本

### `@SpringBootApplication` 三合一
```
@SpringBootApplication = @Configuration + @EnableAutoConfiguration + @ComponentScan
```
- `@Configuration` — 标记呢个class系配置class
- `@EnableAutoConfiguration` — 根据classpath自动配置（例如有spring-boot-starter-web就自动配Tomcat）
- `@ComponentScan` — scan当前package同所有sub-packages，搵标注咗annotation既class

### 分层架构（Layered Architecture）
```
Controller → Service → Repository → Database
```

**点解要分层？（面试必问）**
1. **Single Responsibility** — 每层只做一件事
2. **可替换性** — 换database只改Repository层
3. **Testability** — 可以mock每一层独立测试
4. **Team collaboration** — 唔同人改唔同层，减少conflict

面试标准答法：
> "We use a layered architecture to achieve separation of concerns. The Controller handles HTTP concerns, the Service encapsulates business logic, and the Repository abstracts data access. This makes each layer independently testable, replaceable, and easier to maintain."

### `@RestController` vs `@Controller`
- `@RestController` = `@Controller` + `@ResponseBody`
- return值直接写入HTTP response body（通常JSON）
- `@Controller` 既return值会被当成view name去搵template

### Maven `pom.xml` 四大功能
1. **Dependency management** — 定义project用咩library
2. **Project metadata** — groupId, artifactId, version（project身份证）
3. **Build configuration** — 点样compile、点样package
4. **Plugin management** — 例如spring-boot-maven-plugin

### Maven Project Structure
```
src/
├── main/
│   ├── java/          ← source code
│   └── resources/     ← 配置文件 (application.properties)
└── test/
    └── java/          ← test code
```
注意：src同test唔系分开两个folder，而系都喺src入面分main同test。

### Component Scan 陷阱
`@SpringBootApplication` 只scan自己所在package同sub-packages。
- ✅ `com.onlineshopping.controller` — 会被scan
- ❌ `com.other.service` — 唔会被scan

### `spring-boot-starter-parent` 版本管理
继承parent之后，dependencies唔使写`<version>`，parent已经定义咗compatible版本。

---

## 今日写既Code

### Project结构
```
com.onlineshopping/
├── OnlineShoppingApplication.java    ← @SpringBootApplication 入口
├── controller/
│   └── HealthController.java         ← GET /health → "OK"
├── service/                          ← (下课用)
├── repository/                       ← (下课用)
├── model/                            ← (下课用)
└── config/                           ← (下课用)
```

### Tech Stack
- Java 21 + Maven + Spring Boot 3.5.12
- `spring-boot-starter-web`（Tomcat + Spring MVC + Jackson）
- `spring-boot-starter-test`（JUnit 5 + MockMvc）

---

## `@Component` vs `@Service` vs `@Repository` vs `@Controller`

全部都系 `@Component` 既specialization，功能上用 `@Component` 标注全部class一样work。分开用既原因：

1. **语义清晰** — 一眼知道class既角色
2. **特殊行为**：
   - `@Repository` — 自动将database exception translate成Spring `DataAccessException`
   - `@Controller` — 配合Spring MVC做request mapping
3. **AOP targeting** — 可以针对某一层做aspect（例如所有@Service加logging）

面试答法：
> "They're all specializations of @Component. But @Repository adds automatic exception translation, and @Controller integrates with Spring MVC. Specific annotations also improve readability and enable targeted AOP."

## Spring IoC Container（控制反转）

**传统：** 你既code自己create dependency → 你控制
**IoC：** Spring container帮你create同inject dependency → 控制权反转咗

流程：
1. Spring Boot启动 → component scan搵到所有annotation标注既class
2. 注册成 **Bean**（Spring管理既object instance）
3. 分析每个Bean既constructor需要咩dependencies
4. 按依赖关系自动create同inject

好处（面试必答）：
- **Loose coupling** — class唔知道具体用边个实现，容易替换
- **Testability** — test时inject mock object
- **Lifecycle management** — Spring管object创建/销毁，default singleton

---

## 面试题自测

1. **「`@SpringBootApplication` 背后做咗咩？」**
2. **「点解要分Controller/Service/Repository层？」**
3. **「如果Service class放喺 `com.other.service`，Spring会唔会scan到？点解？」**
4. **「`@RestController` 同 `@Controller` 有咩分别？」**
5. **「pom.xml入面dependency冇写version，Maven点知用边个版本？」**
6. **「`@Component`、`@Service`、`@Repository` 有咩分别？用 `@Component` 标注全部得唔得？」**
7. **「解释下Spring IoC Container点样work？有咩好处？」**

---

## 下课预告：Lesson 2 — E-Commerce Database Design
- ER modeling & normalization
- MySQL + Spring Data JPA
- 用户/商品/订单/分类 schema设计
- Relational DB vs NoSQL 选择
