# Spring Cloud Gateway 生产级学习场景 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新建 4 个模块（gateway-server, user-service, order-service, auth-service），通过 5 套递进 profile 配置实现从基础路由到全链路可观测的 Spring Cloud Gateway 完整学习体系。

**Architecture:** gateway-server (8080, WebFlux/Reactor Netty) 作为统一入口，通过 Nacos 服务发现路由到 3 个下游 Servlet 服务（user:10071, order:10072, auth:10073），Redis 支撑限流和 Token 黑名单，Resilience4J 提供熔断。

**Tech Stack:** Spring Boot 4.0.6, Spring Cloud 2025.1.0, Spring Cloud Alibaba 2025.1.0.0, Java 21, Nacos, Redis, jjwt 0.12+, Resilience4J, Micrometer

## Global Constraints

- Spring Boot 版本: 4.0.6（父 POM 已锁定）
- Spring Cloud 版本: 2025.1.0（父 POM 已锁定）
- Spring Cloud Alibaba 版本: 2025.1.0.0（父 POM 已锁定）
- Java 版本: 21
- 包名前缀: `com.liepig.<module>`
- gateway-server 基于 WebFlux（不可引入 spring-boot-starter-web），下游服务基于 Servlet
- 所有模块注册到 Nacos（地址: 127.0.0.1:8848）
- JWT 使用 jjwt 0.12+（io.jsonwebtoken），HS256 对称密钥
- gateway-server 不使用 spring-boot-starter-web（与 WebFlux 冲突）
- gateway-server Redis 客户端使用 spring-boot-starter-data-redis-reactive（响应式）

---

### Task 1: 更新父 POM 与模块声明

**Files:**
- Modify: `pom.xml`

**Interfaces:**
- Consumes: nothing
- Produces: `<modules>` 声明 gateway-server, user-service, order-service, auth-service

- [ ] **Step 1: 更新 modules 列表**

将 `pom.xml` 的 `<modules>` 替换为:

```xml
<modules>
    <module>server-10070</module>
    <module>gateway-server</module>
    <module>user-service</module>
    <module>order-service</module>
    <module>auth-service</module>
</modules>
```

- [ ] **Step 2: 验证父 POM 可解析**

```bash
cd h:/ProgramSourceCode/IDEA/spring-cloud-2025 && mvn validate
```

Expected: BUILD SUCCESS（不会因新模块缺失而失败，因为还未创建，只是声明）

- [ ] **Step 3: Commit**

```bash
git add pom.xml && git commit -m "build: add gateway-server, user-service, order-service, auth-service modules"
```

---

### Task 2: 创建 user-service 模块

**Files:**
- Create: `user-service/pom.xml`
- Create: `user-service/src/main/java/com/liepig/user/UserServiceApplication.java`
- Create: `user-service/src/main/java/com/liepig/user/controller/UserController.java`
- Create: `user-service/src/main/java/com/liepig/user/model/User.java`
- Create: `user-service/src/main/resources/application.yml`

**Interfaces:**
- Consumes: 父 POM 依赖管理
- Produces:
  - `UserController`: `GET /users/{id}` → `User`, `GET /users` → `List<User>`, `POST /users` → `User`, `GET /users/slow` → `String`（3s 延迟，供熔断演示）

- [ ] **Step 1: 创建 user-service/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.liepig</groupId>
        <artifactId>spring-cloud-2025</artifactId>
        <version>1.0-SNAPSHOT</version>
    </parent>

    <artifactId>user-service</artifactId>

    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-openfeign</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-loadbalancer</artifactId>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 2: 创建 UserServiceApplication.java**

```java
package com.liepig.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class UserServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
```

- [ ] **Step 3: 创建 User 模型**

```java
package com.liepig.user.model;

public class User {
    private Long id;
    private String name;
    private String email;

    public User() {}

    public User(Long id, String name, String email) {
        this.id = id;
        this.name = name;
        this.email = email;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}
```

- [ ] **Step 4: 创建 UserController.java**

```java
package com.liepig.user.controller;

import com.liepig.user.model.User;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@RestController
public class UserController {

    private final Map<Long, User> store = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong(1);

    {
        store.put(1L, new User(1L, "Alice", "alice@example.com"));
        store.put(2L, new User(2L, "Bob", "bob@example.com"));
        idGen.set(3);
    }

    @GetMapping("/users/{id}")
    public User getById(@PathVariable Long id) {
        User user = store.get(id);
        if (user == null) {
            throw new RuntimeException("User not found: " + id);
        }
        return user;
    }

    @GetMapping("/users")
    public List<User> list() {
        return List.copyOf(store.values());
    }

    @PostMapping("/users")
    public User create(@RequestBody User user) {
        user.setId(idGen.getAndIncrement());
        store.put(user.getId(), user);
        return user;
    }

    @GetMapping("/users/slow")
    public String slow() throws InterruptedException {
        Thread.sleep(3000);
        return "slow response from user-service";
    }
}
```

- [ ] **Step 5: 创建 application.yml**

```yaml
server:
  port: 10071
spring:
  application:
    name: user-service
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
    loadbalancer:
      nacos:
        enabled: true
```

- [ ] **Step 6: 编译验证**

```bash
cd h:/ProgramSourceCode/IDEA/spring-cloud-2025 && mvn compile -pl user-service
```

Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add user-service/ && git commit -m "feat: add user-service module with CRUD endpoints"
```

---

### Task 3: 创建 order-service 模块

**Files:**
- Create: `order-service/pom.xml`
- Create: `order-service/src/main/java/com/liepig/order/OrderServiceApplication.java`
- Create: `order-service/src/main/java/com/liepig/order/controller/OrderController.java`
- Create: `order-service/src/main/java/com/liepig/order/model/Order.java`
- Create: `order-service/src/main/java/com/liepig/order/feign/UserFeignClient.java`
- Create: `order-service/src/main/resources/application.yml`

**Interfaces:**
- Consumes: `GET /users/{id}` on user-service（通过 Feign 调用）
- Produces:
  - `UserFeignClient`: Feign 接口，调用 user-service
  - `OrderController`: `GET /orders/{id}` → `Order`（含 user 信息）, `POST /orders` → `Order`
  - `OrderController`: `GET /orders/error` → 抛出 500（供熔断演示）

- [ ] **Step 1: 创建 order-service/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.liepig</groupId>
        <artifactId>spring-cloud-2025</artifactId>
        <version>1.0-SNAPSHOT</version>
    </parent>

    <artifactId>order-service</artifactId>

    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-openfeign</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-loadbalancer</artifactId>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 2: 创建 OrderServiceApplication.java**

```java
package com.liepig.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
```

- [ ] **Step 3: 创建 Order 模型**

```java
package com.liepig.order.model;

public class Order {
    private Long id;
    private Long userId;
    private String product;
    private Object user; // 来自 user-service 的用户信息

    public Order() {}

    public Order(Long id, Long userId, String product) {
        this.id = id;
        this.userId = userId;
        this.product = product;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getProduct() { return product; }
    public void setProduct(String product) { this.product = product; }
    public Object getUser() { return user; }
    public void setUser(Object user) { this.user = user; }
}
```

- [ ] **Step 4: 创建 UserFeignClient.java**

```java
package com.liepig.order.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "user-service")
public interface UserFeignClient {
    @GetMapping("/users/{id}")
    Object getUserById(@PathVariable("id") Long id);
}
```

- [ ] **Step 5: 创建 OrderController.java**

```java
package com.liepig.order.controller;

import com.liepig.order.feign.UserFeignClient;
import com.liepig.order.model.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@RestController
public class OrderController {

    private final Map<Long, Order> store = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong(1);

    @Autowired
    private UserFeignClient userFeignClient;

    @GetMapping("/orders/{id}")
    public Order getById(@PathVariable Long id) {
        Order order = store.get(id);
        if (order == null) {
            throw new RuntimeException("Order not found: " + id);
        }
        // 通过 Feign 获取用户信息填充到订单
        try {
            order.setUser(userFeignClient.getUserById(order.getUserId()));
        } catch (Exception e) {
            order.setUser("user-service unavailable");
        }
        return order;
    }

    @PostMapping("/orders")
    public Order create(@RequestBody Order order) {
        order.setId(idGen.getAndIncrement());
        store.put(order.getId(), order);
        return order;
    }

    @GetMapping("/orders/error")
    public String error() {
        throw new RuntimeException("Simulated order-service internal error");
    }
}
```

- [ ] **Step 6: 创建 application.yml**

```yaml
server:
  port: 10072
spring:
  application:
    name: order-service
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
    loadbalancer:
      nacos:
        enabled: true
```

- [ ] **Step 7: 编译验证**

```bash
cd h:/ProgramSourceCode/IDEA/spring-cloud-2025 && mvn compile -pl order-service
```

Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add order-service/ && git commit -m "feat: add order-service module with Feign call to user-service"
```

---

### Task 4: 创建 auth-service 模块

**Files:**
- Create: `auth-service/pom.xml`
- Create: `auth-service/src/main/java/com/liepig/auth/AuthServiceApplication.java`
- Create: `auth-service/src/main/java/com/liepig/auth/model/LoginRequest.java`
- Create: `auth-service/src/main/java/com/liepig/auth/model/LoginResponse.java`
- Create: `auth-service/src/main/java/com/liepig/auth/util/JwtUtil.java`
- Create: `auth-service/src/main/java/com/liepig/auth/controller/AuthController.java`
- Create: `auth-service/src/main/resources/application.yml`

**Interfaces:**
- Consumes: `spring-boot-starter-data-redis`（Jedis 客户端，非 reactive）, jjwt
- Produces:
  - `JwtUtil`: `generateToken(userId, username, roles)` → `String`, `validateToken(token)` → `Claims`, `isBlacklisted(token)` → `boolean`
  - `AuthController`:
    - `POST /auth/login` — 接收 `LoginRequest`，返回 `LoginResponse {token, expiresIn}`
    - `POST /auth/verify` — 接收 `Authorization` header，返回 `{valid:boolean, userId, username, roles}`
    - `POST /auth/logout` — 将 token 写入 Redis 黑名单

- [ ] **Step 1: 创建 auth-service/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.liepig</groupId>
        <artifactId>spring-cloud-2025</artifactId>
        <version>1.0-SNAPSHOT</version>
    </parent>

    <artifactId>auth-service</artifactId>

    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-openfeign</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-loadbalancer</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
            <version>0.12.6</version>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <version>0.12.6</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <version>0.12.6</version>
            <scope>runtime</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 2: 创建 AuthServiceApplication.java**

```java
package com.liepig.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class AuthServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
```

- [ ] **Step 3: 创建 LoginRequest.java**

```java
package com.liepig.auth.model;

public class LoginRequest {
    private String username;
    private String password;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
```

- [ ] **Step 4: 创建 LoginResponse.java**

```java
package com.liepig.auth.model;

public class LoginResponse {
    private String token;
    private long expiresIn;

    public LoginResponse() {}

    public LoginResponse(String token, long expiresIn) {
        this.token = token;
        this.expiresIn = expiresIn;
    }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public long getExpiresIn() { return expiresIn; }
    public void setExpiresIn(long expiresIn) { this.expiresIn = expiresIn; }
}
```

- [ ] **Step 5: 创建 JwtUtil.java**

```java
package com.liepig.auth.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class JwtUtil {

    // HS256 requires key >= 256 bits (32 bytes)
    private static final String SECRET = "spring-cloud-gateway-learning-2025-secret-key-32bytes!";
    private static final long EXPIRATION_MS = 3600_000; // 1 hour

    private final SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    @Autowired
    private StringRedisTemplate redisTemplate;

    public String generateToken(Long userId, String username, List<String> roles) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + EXPIRATION_MS);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("roles", roles)
                .issuedAt(now)
                .expiration(exp)
                .signWith(key)
                .compact();
    }

    public Claims validateToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public void blacklist(String token) {
        Claims claims = validateToken(token);
        long remainingMs = claims.getExpiration().getTime() - System.currentTimeMillis();
        if (remainingMs > 0) {
            redisTemplate.opsForValue().set(
                    "blacklist:token:" + token, "1", remainingMs, TimeUnit.MILLISECONDS);
        }
    }

    public boolean isBlacklisted(String token) {
        return Boolean.TRUE.equals(redisTemplate.hasKey("blacklist:token:" + token));
    }
}
```

- [ ] **Step 6: 创建 AuthController.java**

```java
package com.liepig.auth.controller;

import com.liepig.auth.model.LoginRequest;
import com.liepig.auth.model.LoginResponse;
import com.liepig.auth.util.JwtUtil;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class AuthController {

    // 模拟用户数据库
    private static final Map<String, String> USERS = Map.of(
            "admin", "123456",
            "user", "123456"
    );

    @Autowired
    private JwtUtil jwtUtil;

    @PostMapping("/auth/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        String expectedPassword = USERS.get(request.getUsername());
        if (expectedPassword == null || !expectedPassword.equals(request.getPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid credentials"));
        }
        List<String> roles = "admin".equals(request.getUsername())
                ? List.of("admin", "user")
                : List.of("user");
        String token = jwtUtil.generateToken(
                request.getUsername().hashCode() & 0x7FFFFFFFL,
                request.getUsername(),
                roles);
        return ResponseEntity.ok(new LoginResponse(token, 3600));
    }

    @PostMapping("/auth/verify")
    public ResponseEntity<?> verify(@RequestHeader("Authorization") String authHeader) {
        try {
            String token = extractToken(authHeader);
            Claims claims = jwtUtil.validateToken(token);
            if (jwtUtil.isBlacklisted(token)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("valid", false, "reason", "token blacklisted"));
            }
            return ResponseEntity.ok(Map.of(
                    "valid", true,
                    "userId", claims.getSubject(),
                    "username", claims.get("username", String.class),
                    "roles", claims.get("roles", List.class)
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("valid", false, "reason", e.getMessage()));
        }
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<?> logout(@RequestHeader("Authorization") String authHeader) {
        try {
            String token = extractToken(authHeader);
            jwtUtil.blacklist(token);
            return ResponseEntity.ok(Map.of("message", "logged out"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    private String extractToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new RuntimeException("Missing or invalid Authorization header");
        }
        return authHeader.substring(7);
    }
}
```

- [ ] **Step 7: 创建 application.yml**

```yaml
server:
  port: 10073
spring:
  application:
    name: auth-service
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
    loadbalancer:
      nacos:
        enabled: true
  data:
    redis:
      host: 127.0.0.1
      port: 6379
```

- [ ] **Step 8: 编译验证**

```bash
cd h:/ProgramSourceCode/IDEA/spring-cloud-2025 && mvn compile -pl auth-service
```

Expected: BUILD SUCCESS

- [ ] **Step 9: Commit**

```bash
git add auth-service/ && git commit -m "feat: add auth-service module with JWT login/logout/verify"
```

---

### Task 5: 创建 gateway-server 模块骨架

**Files:**
- Create: `gateway-server/pom.xml`
- Create: `gateway-server/src/main/java/com/liepig/gateway/GatewayApplication.java`
- Create: `gateway-server/src/main/resources/application.yml`

**Interfaces:**
- Consumes: 父 POM 依赖管理
- Produces: Gateway 主应用类，公共 `application.yml`

> **重要**: gateway-server 基于 WebFlux，不可引入 `spring-boot-starter-web`。Redis 客户端用 reactive 版本。

- [ ] **Step 1: 创建 gateway-server/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.liepig</groupId>
        <artifactId>spring-cloud-2025</artifactId>
        <version>1.0-SNAPSHOT</version>
    </parent>

    <artifactId>gateway-server</artifactId>

    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <!-- Gateway (WebFlux-based, do NOT add spring-boot-starter-web) -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-gateway</artifactId>
        </dependency>
        <!-- Nacos Discovery -->
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
        </dependency>
        <!-- LoadBalancer -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-loadbalancer</artifactId>
        </dependency>
        <!-- CircuitBreaker + Resilience4J (Scenario 4) -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-circuitbreaker-reactor-resilience4j</artifactId>
        </dependency>
        <!-- Redis Reactive for rate limiting (Scenario 4) + token blacklist (Scenario 3) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis-reactive</artifactId>
        </dependency>
        <!-- JWT (Scenario 3) -->
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
            <version>0.12.6</version>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <version>0.12.6</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <version>0.12.6</version>
            <scope>runtime</scope>
        </dependency>
        <!-- Actuator (Scenario 5) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 2: 创建 GatewayApplication.java**

```java
package com.liepig.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
```

- [ ] **Step 3: 创建 application.yml（公共配置）**

```yaml
server:
  port: 8080
spring:
  application:
    name: gateway-server
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
    loadbalancer:
      nacos:
        enabled: true
```

- [ ] **Step 4: 编译验证**

```bash
cd h:/ProgramSourceCode/IDEA/spring-cloud-2025 && mvn compile -pl gateway-server
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add gateway-server/ && git commit -m "feat: add gateway-server module skeleton (WebFlux + Nacos + Redis + JWT)"
```

---

### Task 6: 场景 1 — 基础路由 + CORS 配置

**Files:**
- Create: `gateway-server/src/main/resources/application-scenario1.yml`
- Create: `gateway-server/src/main/java/com/liepig/gateway/config/CorsConfig.java`

**Interfaces:**
- Consumes: `application.yml` 公共配置
- Produces: 3 条路由规则 + 全局 CORS

- [ ] **Step 1: 创建 application-scenario1.yml**

```yaml
spring:
  cloud:
    gateway:
      discovery:
        locator:
          enabled: true
          lower-case-service-id: true
      routes:
        - id: user-service-route
          uri: lb://user-service
          predicates:
            - Path=/api/user/**
          filters:
            - StripPrefix=2

        - id: order-service-route
          uri: lb://order-service
          predicates:
            - Path=/api/order/**
          filters:
            - StripPrefix=2

        - id: auth-service-route
          uri: lb://auth-service
          predicates:
            - Path=/api/auth/**
          filters:
            - StripPrefix=2
```

- [ ] **Step 2: 创建 CorsConfig.java**

```java
package com.liepig.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class CorsConfig {

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
cd h:/ProgramSourceCode/IDEA/spring-cloud-2025 && mvn compile -pl gateway-server
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add gateway-server/src/main/resources/application-scenario1.yml gateway-server/src/main/java/com/liepig/gateway/config/CorsConfig.java && git commit -m "feat: scenario1 - basic routing with Nacos LB + CORS"
```

---

### Task 7: 场景 2 — 自定义过滤器

**Files:**
- Create: `gateway-server/src/main/java/com/liepig/gateway/filter/LoggingGlobalFilter.java`
- Create: `gateway-server/src/main/java/com/liepig/gateway/filter/RequestHeaderGlobalFilter.java`
- Create: `gateway-server/src/main/java/com/liepig/gateway/filter/factory/ValidateHeaderGatewayFilterFactory.java`
- Modify: `gateway-server/src/main/resources/application-scenario2.yml`（新建）

**Interfaces:**
- Consumes: 场景 1 路由配置
- Produces:
  - `LoggingGlobalFilter`: Order=-1, 记录请求耗时
  - `RequestHeaderGlobalFilter`: Order=0, 注入 `X-Gateway-Id`, `X-Gateway-Timestamp`
  - `ValidateHeaderGatewayFilterFactory`: 校验 `X-Client-Version` header

- [ ] **Step 1: 创建 LoggingGlobalFilter.java**

```java
package com.liepig.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class LoggingGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(LoggingGlobalFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long start = System.currentTimeMillis();
        String path = exchange.getRequest().getURI().getPath();
        String method = exchange.getRequest().getMethod().name();

        return chain.filter(exchange).doFinally(signalType -> {
            long duration = System.currentTimeMillis() - start;
            int status = exchange.getResponse().getStatusCode() != null
                    ? exchange.getResponse().getStatusCode().value() : 0;
            log.info("{} {} -> {} ({}ms)", method, path, status, duration);
        });
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
```

- [ ] **Step 2: 创建 RequestHeaderGlobalFilter.java**

```java
package com.liepig.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@Component
public class RequestHeaderGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String gatewayId = "gateway-" + UUID.randomUUID().toString().substring(0, 6);
        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.header("X-Gateway-Id", gatewayId)
                        .header("X-Gateway-Timestamp", Instant.now().toString()))
                .build();
        return chain.filter(mutated);
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
```

- [ ] **Step 3: 创建 ValidateHeaderGatewayFilterFactory.java**

```java
package com.liepig.gateway.filter.factory;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ValidateHeaderGatewayFilterFactory
        extends AbstractGatewayFilterFactory<ValidateHeaderGatewayFilterFactory.Config> {

    public ValidateHeaderGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String headerValue = exchange.getRequest().getHeaders().getFirst(config.headerName);
            if (headerValue == null || headerValue.isBlank()) {
                exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
                return exchange.getResponse().setComplete();
            }
            return chain.filter(exchange);
        };
    }

    public static class Config {
        private String headerName = "X-Client-Version";

        public String getHeaderName() { return headerName; }
        public void setHeaderName(String headerName) { this.headerName = headerName; }
    }
}
```

- [ ] **Step 4: 创建 application-scenario2.yml**

```yaml
spring:
  cloud:
    gateway:
      discovery:
        locator:
          enabled: true
          lower-case-service-id: true
      routes:
        - id: user-service-route
          uri: lb://user-service
          predicates:
            - Path=/api/user/**
          filters:
            - StripPrefix=2
            - ValidateHeader=X-Client-Version
            - AddResponseHeader=X-Gateway-Node, gateway-server

        - id: order-service-route
          uri: lb://order-service
          predicates:
            - Path=/api/order/**
          filters:
            - StripPrefix=2
            - ValidateHeader=X-Client-Version
            - AddResponseHeader=X-Gateway-Node, gateway-server

        - id: auth-service-route
          uri: lb://auth-service
          predicates:
            - Path=/api/auth/**
          filters:
            - StripPrefix=2
            # auth 路由不做 Client-Version 校验（登录接口需公开访问）
```

- [ ] **Step 5: 编译验证**

```bash
cd h:/ProgramSourceCode/IDEA/spring-cloud-2025 && mvn compile -pl gateway-server
```

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add gateway-server/src/main/java/com/liepig/gateway/filter/ gateway-server/src/main/resources/application-scenario2.yml && git commit -m "feat: scenario2 - LoggingFilter, RequestHeaderFilter, ValidateHeader GatewayFilterFactory"
```

---

### Task 8: 场景 3 — 安全认证 (AuthGlobalFilter)

**Files:**
- Create: `gateway-server/src/main/java/com/liepig/gateway/filter/AuthGlobalFilter.java`
- Create: `gateway-server/src/main/java/com/liepig/gateway/config/RedisConfig.java`
- Create: `gateway-server/src/main/resources/application-scenario3.yml`

**Interfaces:**
- Consumes: auth-service 的 JwtUtil 逻辑，Redis 黑名单
- Produces:
  - `AuthGlobalFilter`: Order=-100, JWT 校验 + Token 中继 + 白名单 + IP 限制

- [ ] **Step 1: 创建 AuthGlobalFilter.java**

```java
package com.liepig.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuthGlobalFilter.class);
    private static final String SECRET = "spring-cloud-gateway-learning-2025-secret-key-32bytes!";
    private static final Set<String> WHITELIST_PATHS = Set.of(
            "/api/auth/auth/login", "/api/auth/auth/logout"
    );
    // Public paths that don't need auth
    private static final Set<String> PUBLIC_PREFIXES = Set.of(
            "/api/public"
    );

    private final SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    @Autowired
    private ReactiveStringRedisTemplate redisTemplate;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // Skip whitelisted paths and public paths
        if (WHITELIST_PATHS.contains(path)) {
            return chain.filter(exchange);
        }
        for (String prefix : PUBLIC_PREFIXES) {
            if (path.startsWith(prefix)) {
                return chain.filter(exchange);
            }
        }

        // Extract token
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorized(exchange, "Missing or invalid Authorization header");
        }
        String token = authHeader.substring(7);

        // Validate JWT signature + expiry
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            return unauthorized(exchange, "Invalid token: " + e.getMessage());
        }

        // Check blacklist (Redis)
        return redisTemplate.hasKey("blacklist:token:" + token)
                .flatMap(isBlacklisted -> {
                    if (Boolean.TRUE.equals(isBlacklisted)) {
                        return unauthorized(exchange, "Token has been revoked");
                    }

                    // Relay user info to downstream
                    String userId = claims.getSubject();
                    String username = claims.get("username", String.class);
                    @SuppressWarnings("unchecked")
                    List<String> roles = claims.get("roles", List.class);

                    ServerWebExchange mutated = exchange.mutate()
                            .request(r -> r.header("X-User-Id", userId)
                                    .header("X-User-Name", username != null ? username : "")
                                    .header("X-User-Roles", String.join(",", roles != null ? roles : List.of())))
                            .build();

                    log.debug("Auth OK: userId={}, path={}", userId, path);
                    return chain.filter(mutated);
                });
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        log.warn("Auth FAIL: {}", message);
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
        byte[] body = ("{\"code\":401,\"msg\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse()
                .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    @Override
    public int getOrder() {
        return -100; // Before all other filters
    }
}
```

- [ ] **Step 2: 创建 RedisConfig.java**

gateway-server 的 auth-service 路由不需要 `ValidateHeader` filter（登录接口是公开的），所以在 scenario3 配置中显式排除。

```java
package com.liepig.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

@Configuration
public class RedisConfig {

    @Bean
    public ReactiveStringRedisTemplate reactiveStringRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory) {
        return new ReactiveStringRedisTemplate(connectionFactory);
    }
}
```

- [ ] **Step 3: 创建 application-scenario3.yml**

```yaml
spring:
  cloud:
    gateway:
      discovery:
        locator:
          enabled: true
          lower-case-service-id: true
      routes:
        - id: user-service-route
          uri: lb://user-service
          predicates:
            - Path=/api/user/**
          filters:
            - StripPrefix=2
            - ValidateHeader=X-Client-Version
            - AddResponseHeader=X-Gateway-Node, gateway-server

        - id: order-service-route
          uri: lb://order-service
          predicates:
            - Path=/api/order/**
          filters:
            - StripPrefix=2
            - ValidateHeader=X-Client-Version
            - AddResponseHeader=X-Gateway-Node, gateway-server

        - id: auth-service-route
          uri: lb://auth-service
          predicates:
            - Path=/api/auth/**
          filters:
            - StripPrefix=2

        # Admin endpoints restricted to localhost/internal IPs
        - id: admin-route
          uri: lb://user-service
          predicates:
            - Path=/admin/**
            - RemoteAddr=127.0.0.1,192.168.0.0/16
          filters:
            - StripPrefix=1
  data:
    redis:
      host: 127.0.0.1
      port: 6379
```

- [ ] **Step 4: 编译验证**

```bash
cd h:/ProgramSourceCode/IDEA/spring-cloud-2025 && mvn compile -pl gateway-server
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add gateway-server/src/main/java/com/liepig/gateway/filter/AuthGlobalFilter.java gateway-server/src/main/java/com/liepig/gateway/config/RedisConfig.java gateway-server/src/main/resources/application-scenario3.yml && git commit -m "feat: scenario3 - AuthGlobalFilter with JWT validation, token relay, Redis blacklist, IP restriction"
```

---

### Task 9: 场景 4 — 流量控制与弹性

**Files:**
- Create: `gateway-server/src/main/java/com/liepig/gateway/config/GatewayRateLimiterConfig.java`
- Create: `gateway-server/src/main/java/com/liepig/gateway/controller/FallbackController.java`
- Create: `gateway-server/src/main/resources/application-scenario4.yml`

**Interfaces:**
- Consumes: Redis, Resilience4J CircuitBreaker
- Produces:
  - `GatewayRateLimiterConfig`: `KeyResolver` beans（user + ip）
  - `FallbackController`: `GET /fallback/order-service` → 降级 JSON

- [ ] **Step 1: 创建 GatewayRateLimiterConfig.java**

```java
package com.liepig.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.util.Objects;

@Configuration
public class GatewayRateLimiterConfig {

    /**
     * Rate limit by authenticated user (X-User-Id header).
     * Falls back to IP if unauthenticated.
     */
    @Bean("userKeyResolver")
    public KeyResolver userKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            if (userId != null && !userId.isBlank()) {
                return Mono.just("user:" + userId);
            }
            // Fallback to IP
            String ip = Objects.requireNonNull(
                    exchange.getRequest().getRemoteAddress()).getAddress().getHostAddress();
            return Mono.just("ip:" + ip);
        };
    }

    @Bean("ipKeyResolver")
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just("ip:" + Objects.requireNonNull(
                exchange.getRequest().getRemoteAddress()).getAddress().getHostAddress());
    }
}
```

- [ ] **Step 2: 创建 FallbackController.java**

```java
package com.liepig.gateway.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
public class FallbackController {

    @GetMapping("/fallback/order-service")
    public Mono<Map<String, Object>> orderServiceFallback() {
        return Mono.just(Map.of(
                "code", 503,
                "msg", "订单服务繁忙，请稍后重试",
                "timestamp", System.currentTimeMillis()
        ));
    }
}
```

- [ ] **Step 3: 创建 application-scenario4.yml**

```yaml
spring:
  cloud:
    gateway:
      discovery:
        locator:
          enabled: true
          lower-case-service-id: true
      routes:
        - id: user-service-route
          uri: lb://user-service
          predicates:
            - Path=/api/user/**
          filters:
            - StripPrefix=2
            - ValidateHeader=X-Client-Version
            - AddResponseHeader=X-Gateway-Node, gateway-server
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 5
                redis-rate-limiter.burstCapacity: 10
                key-resolver: "#{@userKeyResolver}"

        - id: order-service-route
          uri: lb://order-service
          predicates:
            - Path=/api/order/**
          filters:
            - StripPrefix=2
            - ValidateHeader=X-Client-Version
            - AddResponseHeader=X-Gateway-Node, gateway-server
            - name: CircuitBreaker
              args:
                name: orderServiceCB
                fallbackUri: forward:/fallback/order-service
            - name: Retry
              args:
                retries: 3
                statuses: BAD_GATEWAY,SERVICE_UNAVAILABLE,GATEWAY_TIMEOUT
                methods: POST,PUT
                backoff:
                  firstBackoff: 500ms
                  maxBackoff: 2000ms
                  factor: 2

        - id: auth-service-route
          uri: lb://auth-service
          predicates:
            - Path=/api/auth/**
          filters:
            - StripPrefix=2

        - id: admin-route
          uri: lb://user-service
          predicates:
            - Path=/admin/**
            - RemoteAddr=127.0.0.1,192.168.0.0/16
          filters:
            - StripPrefix=1

      # Global HTTP client timeouts
      httpclient:
        connect-timeout: 3000
        response-timeout: 5s

  data:
    redis:
      host: 127.0.0.1
      port: 6379

# Resilience4J CircuitBreaker configuration
resilience4j:
  circuitbreaker:
    configs:
      default:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 5s
        permitted-number-of-calls-in-half-open-state: 3
    instances:
      orderServiceCB:
        base-config: default
  timelimiter:
    configs:
      default:
        timeout-duration: 5s
```

- [ ] **Step 4: 编译验证**

```bash
cd h:/ProgramSourceCode/IDEA/spring-cloud-2025 && mvn compile -pl gateway-server
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add gateway-server/src/main/java/com/liepig/gateway/config/GatewayRateLimiterConfig.java gateway-server/src/main/java/com/liepig/gateway/controller/FallbackController.java gateway-server/src/main/resources/application-scenario4.yml && git commit -m "feat: scenario4 - Redis rate limiting, CircuitBreaker with fallback, Retry, timeout control"
```

---

### Task 10: 场景 5 — 全链路可观测

**Files:**
- Create: `gateway-server/src/main/java/com/liepig/gateway/filter/TraceIdGlobalFilter.java`
- Create: `gateway-server/src/main/java/com/liepig/gateway/filter/AccessLogFilter.java`
- Create: `gateway-server/src/main/java/com/liepig/gateway/controller/StatsController.java`
- Create: `user-service/src/main/java/com/liepig/user/filter/TraceIdServletFilter.java`
- Create: `order-service/src/main/java/com/liepig/order/filter/TraceIdServletFilter.java`
- Create: `order-service/src/main/java/com/liepig/order/config/FeignTraceInterceptor.java`
- Create: `auth-service/src/main/java/com/liepig/auth/filter/TraceIdServletFilter.java`
- Create: `gateway-server/src/main/resources/logback-spring.xml`
- Create: `user-service/src/main/resources/logback-spring.xml`
- Create: `order-service/src/main/resources/logback-spring.xml`
- Create: `auth-service/src/main/resources/logback-spring.xml`
- Create: `gateway-server/src/main/resources/application-scenario5.yml`

**Interfaces:**
- Consumes: 场景 4 全部能力
- Produces:
  - `TraceIdGlobalFilter`: Order=-200, 生成/提取 `X-Trace-Id`
  - `AccessLogFilter`: Order=100, 结构化 JSON 日志
  - `StatsController`: 内存实时统计
  - 各下游服务 TraceId 传递

- [ ] **Step 1: 创建 TraceIdGlobalFilter.java**

```java
package com.liepig.gateway.filter;

import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class TraceIdGlobalFilter implements GlobalFilter, Ordered {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Generate or reuse traceId
        String traceId = exchange.getRequest().getHeaders().getFirst(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
        }

        // Put in MDC for logging
        MDC.put("traceId", traceId);

        // Relay to downstream via request header
        String finalTraceId = traceId;
        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.header(TRACE_ID_HEADER, finalTraceId))
                .build();

        return chain.filter(mutated)
                .doFinally(signalType -> MDC.remove("traceId"));
    }

    @Override
    public int getOrder() {
        return -200;
    }
}
```

- [ ] **Step 2: 创建 AccessLogFilter.java**

```java
package com.liepig.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class AccessLogFilter implements GlobalFilter, Ordered {

    private static final Logger accessLog = LoggerFactory.getLogger("ACCESS_LOG");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long start = System.currentTimeMillis();
        String path = exchange.getRequest().getURI().getPath();
        String method = exchange.getRequest().getMethod().name();
        String traceId = exchange.getRequest().getHeaders().getFirst(TraceIdGlobalFilter.TRACE_ID_HEADER);

        return chain.filter(exchange).doFinally(signalType -> {
            long duration = System.currentTimeMillis() - start;
            int status = exchange.getResponse().getStatusCode() != null
                    ? exchange.getResponse().getStatusCode().value() : 0;
            String upstream = exchange.getAttribute("org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayRoutePredicateRouteId");
            if (upstream == null) upstream = "unknown";

            // Structured JSON log line for ELK/Loki ingestion
            accessLog.info("{{\"traceId\":\"{}\",\"method\":\"{}\",\"path\":\"{}\",\"status\":{},\"durationMs\":{},\"upstream\":\"{}\"}}",
                    traceId, method, path, status, duration, upstream);
        });
    }

    @Override
    public int getOrder() {
        return 100; // After all other filters, near response
    }
}
```

- [ ] **Step 3: 创建 StatsController.java**

```java
package com.liepig.gateway.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@RestController
public class StatsController {

    private final ConcurrentHashMap<String, AtomicLong> counter = new ConcurrentHashMap<>();

    /**
     * Called by a hypothetical interceptor; in a real app you'd wire into
     * the Gateway metrics. For learning we expose a simple counter.
     */
    public void record(String metric) {
        counter.computeIfAbsent(metric, k -> new AtomicLong()).incrementAndGet();
    }

    @GetMapping("/gateway/admin/stats")
    public Mono<Map<String, Object>> stats() {
        Map<String, Object> result = new ConcurrentHashMap<>();
        counter.forEach((k, v) -> result.put(k, v.get()));
        return Mono.just(result);
    }
}
```

- [ ] **Step 4: 创建各服务 TraceIdServletFilter.java（三个服务内容相同，包名不同）**

`user-service/src/main/java/com/liepig/user/filter/TraceIdServletFilter.java`:

```java
package com.liepig.user.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class TraceIdServletFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader("X-Trace-Id");
        if (traceId != null && !traceId.isBlank()) {
            MDC.put("traceId", traceId);
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
        }
    }
}
```

`order-service/src/main/java/com/liepig/order/filter/TraceIdServletFilter.java`:

```java
package com.liepig.order.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class TraceIdServletFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader("X-Trace-Id");
        if (traceId != null && !traceId.isBlank()) {
            MDC.put("traceId", traceId);
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
        }
    }
}
```

`auth-service/src/main/java/com/liepig/auth/filter/TraceIdServletFilter.java`:

```java
package com.liepig.auth.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class TraceIdServletFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader("X-Trace-Id");
        if (traceId != null && !traceId.isBlank()) {
            MDC.put("traceId", traceId);
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
        }
    }
}
```

- [ ] **Step 5: 创建 FeignTraceInterceptor.java（order-service 和 auth-service 各一份）**

`order-service/src/main/java/com/liepig/order/config/FeignTraceInterceptor.java`:

```java
package com.liepig.order.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FeignTraceInterceptor {

    @Bean
    public RequestInterceptor traceIdInterceptor() {
        return (RequestTemplate template) -> {
            String traceId = MDC.get("traceId");
            if (traceId != null && !traceId.isBlank()) {
                template.header("X-Trace-Id", traceId);
            }
        };
    }
}
```

`auth-service/src/main/java/com/liepig/auth/config/FeignTraceInterceptor.java`:

```java
package com.liepig.auth.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FeignTraceInterceptor {

    @Bean
    public RequestInterceptor traceIdInterceptor() {
        return (RequestTemplate template) -> {
            String traceId = MDC.get("traceId");
            if (traceId != null && !traceId.isBlank()) {
                template.header("X-Trace-Id", traceId);
            }
        };
    }
}
```

- [ ] **Step 6: 创建所有模块的 logback-spring.xml**

`gateway-server/src/main/resources/logback-spring.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{ISO8601} [%thread] %-5level [traceId:%X{traceId}] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    <logger name="ACCESS_LOG" level="INFO" additivity="false">
        <appender-ref ref="CONSOLE"/>
    </logger>
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>
</configuration>
```

`user-service/src/main/resources/logback-spring.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{ISO8601} [%thread] %-5level [traceId:%X{traceId}] [user-service] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>
</configuration>
```

`order-service/src/main/resources/logback-spring.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{ISO8601} [%thread] %-5level [traceId:%X{traceId}] [order-service] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>
</configuration>
```

`auth-service/src/main/resources/logback-spring.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{ISO8601} [%thread] %-5level [traceId:%X{traceId}] [auth-service] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>
</configuration>
```

- [ ] **Step 7: 创建 application-scenario5.yml**

```yaml
spring:
  cloud:
    gateway:
      discovery:
        locator:
          enabled: true
          lower-case-service-id: true
      routes:
        - id: user-service-route
          uri: lb://user-service
          predicates:
            - Path=/api/user/**
          filters:
            - StripPrefix=2
            - ValidateHeader=X-Client-Version
            - AddResponseHeader=X-Gateway-Node, gateway-server
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 5
                redis-rate-limiter.burstCapacity: 10
                key-resolver: "#{@userKeyResolver}"

        - id: order-service-route
          uri: lb://order-service
          predicates:
            - Path=/api/order/**
          filters:
            - StripPrefix=2
            - ValidateHeader=X-Client-Version
            - AddResponseHeader=X-Gateway-Node, gateway-server
            - name: CircuitBreaker
              args:
                name: orderServiceCB
                fallbackUri: forward:/fallback/order-service
            - name: Retry
              args:
                retries: 3
                statuses: BAD_GATEWAY,SERVICE_UNAVAILABLE,GATEWAY_TIMEOUT
                methods: POST,PUT
                backoff:
                  firstBackoff: 500ms
                  maxBackoff: 2000ms
                  factor: 2

        - id: auth-service-route
          uri: lb://auth-service
          predicates:
            - Path=/api/auth/**
          filters:
            - StripPrefix=2

        - id: admin-route
          uri: lb://user-service
          predicates:
            - Path=/admin/**
            - RemoteAddr=127.0.0.1,192.168.0.0/16
          filters:
            - StripPrefix=1

      httpclient:
        connect-timeout: 3000
        response-timeout: 5s

  data:
    redis:
      host: 127.0.0.1
      port: 6379

# Actuator - full exposure for scenario 5
management:
  endpoints:
    web:
      exposure:
        include: "*"
  endpoint:
    health:
      show-details: always
    gateway:
      enabled: true

# Resilience4J
resilience4j:
  circuitbreaker:
    configs:
      default:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 5s
        permitted-number-of-calls-in-half-open-state: 3
    instances:
      orderServiceCB:
        base-config: default
  timelimiter:
    configs:
      default:
        timeout-duration: 5s
```

- [ ] **Step 8: 编译所有模块**

```bash
cd h:/ProgramSourceCode/IDEA/spring-cloud-2025 && mvn compile
```

Expected: BUILD SUCCESS（所有模块编译通过）

- [ ] **Step 9: Commit**

```bash
git add gateway-server/src/main/java/com/liepig/gateway/filter/TraceIdGlobalFilter.java gateway-server/src/main/java/com/liepig/gateway/filter/AccessLogFilter.java gateway-server/src/main/java/com/liepig/gateway/controller/StatsController.java gateway-server/src/main/resources/logback-spring.xml gateway-server/src/main/resources/application-scenario5.yml user-service/src/main/java/com/liepig/user/filter/ user-service/src/main/resources/logback-spring.xml order-service/src/main/java/com/liepig/order/filter/ order-service/src/main/java/com/liepig/order/config/FeignTraceInterceptor.java order-service/src/main/resources/logback-spring.xml auth-service/src/main/java/com/liepig/auth/filter/ auth-service/src/main/java/com/liepig/auth/config/FeignTraceInterceptor.java auth-service/src/main/resources/logback-spring.xml && git commit -m "feat: scenario5 - TraceId propagation, structured access logs, Actuator metrics, StatsController"
```

---

### Task 11: 全量编译 + 最终验证

**Files:**
- None (验证任务)

- [ ] **Step 1: 全量编译**

```bash
cd h:/ProgramSourceCode/IDEA/spring-cloud-2025 && mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 2: 验证模块完整性**

```bash
cd h:/ProgramSourceCode/IDEA/spring-cloud-2025 && find . -name "*.java" -path "*/gateway-server/*" | sort
```

Expected: 至少包含以下文件：
```
GatewayApplication.java
config/CorsConfig.java
config/GatewayRateLimiterConfig.java
config/RedisConfig.java
controller/FallbackController.java
controller/StatsController.java
filter/AccessLogFilter.java
filter/AuthGlobalFilter.java
filter/LoggingGlobalFilter.java
filter/RequestHeaderGlobalFilter.java
filter/TraceIdGlobalFilter.java
filter/factory/ValidateHeaderGatewayFilterFactory.java
```

- [ ] **Step 3: 验证配置文件存在**

```bash
ls gateway-server/src/main/resources/application-scenario*.yml
```

Expected: 5 个文件 `scenario1` ~ `scenario5`

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "chore: final verification - all 5 scenarios complete"
```

---

## 启动与手动验证

完成全部 Task 后，按以下顺序验证：

### 前置条件

1. 启动 Nacos（127.0.0.1:8848）
2. 启动 Redis（127.0.0.1:6379）

### 启动下游服务

```bash
# Terminal 1
mvn spring-boot:run -pl user-service

# Terminal 2
mvn spring-boot:run -pl order-service

# Terminal 3
mvn spring-boot:run -pl auth-service
```

### 逐场景验证

```bash
# === 场景 1: 基础路由 ===
mvn spring-boot:run -pl gateway-server -Dspring-boot.run.profiles=scenario1
# curl http://localhost:8080/api/user/users/1
# curl http://localhost:8080/api/order/orders  (note: POST needed)

# === 场景 2: 过滤器链 ===
mvn spring-boot:run -pl gateway-server -Dspring-boot.run.profiles=scenario2
# curl http://localhost:8080/api/user/users/1  → 400 (missing header)
# curl -H "X-Client-Version:1.0" http://localhost:8080/api/user/users/1 → 200

# === 场景 3: 安全认证 ===
mvn spring-boot:run -pl gateway-server -Dspring-boot.run.profiles=scenario3
# curl -X POST http://localhost:8080/api/auth/auth/login -H "Content-Type:application/json" -d '{"username":"admin","password":"123456"}'
# curl -H "Authorization:Bearer <token>" -H "X-Client-Version:1.0" http://localhost:8080/api/user/users/1

# === 场景 4: 限流熔断 ===
mvn spring-boot:run -pl gateway-server -Dspring-boot.run.profiles=scenario4
# for i in {1..15}; do curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization:Bearer <token>" -H "X-Client-Version:1.0" http://localhost:8080/api/user/users/1; done

# === 场景 5: 全链路 ===
mvn spring-boot:run -pl gateway-server -Dspring-boot.run.profiles=scenario5
# curl http://localhost:8080/actuator/gateway/routes
# curl http://localhost:8080/actuator/metrics/spring.cloud.gateway.requests
# curl http://localhost:8080/gateway/admin/stats
```
