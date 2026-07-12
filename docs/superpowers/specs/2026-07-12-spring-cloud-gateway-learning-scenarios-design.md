# Spring Cloud Gateway 生产级学习场景设计

## 概述

基于 Spring Cloud 2025.1.0 + Spring Boot 4.0.6 + Java 21，设计 5 个逐步递进的生产级 Spring Cloud Gateway 学习场景。每个场景在前一个基础上叠加新能力，5 套配置（`application-scenario1.yml` ~ `application-scenario5.yml`）组织在同一 `gateway-server` 模块下。

## 技术栈

| 组件 | 版本 |
|------|------|
| Spring Boot | 4.0.6 |
| Spring Cloud | 2025.1.0 |
| Spring Cloud Alibaba | 2025.1.0.0 |
| Java | 21 |
| 服务注册/发现 | Nacos |
| 服务发现/配置中心 | Nacos |
| RPC | OpenFeign + Spring Cloud LoadBalancer |
| 网关 | Spring Cloud Gateway (Reactor Netty) |
| 限流存储 | Redis |
| 熔断 | Resilience4J + Spring Cloud CircuitBreaker |
| 认证 | JWT (jjwt 0.12+) |
| 可观测 | Micrometer + Actuator |

## 整体架构

```
                    ┌─────────────────────────────────────┐
                    │        gateway-server (8080)         │
                    │  5 profiles: scenario1 ~ scenario5   │
                    │  Route Predicates + Filters          │
                    └──────┬──────────────┬───────────────┘
                           │              │
                    ┌──────▼──────┐  ┌────▼──────┐
                    │    Nacos    │  │   Redis    │
                    │  :8848      │  │  :6379     │
                    └─────────────┘  └────────────┘
                           │
              ┌────────────┼────────────┐
              │            │            │
     ┌───────▼──────┐ ┌───▼──────┐ ┌───▼───────────┐
     │ user-service │ │ order-   │ │ auth-service  │
     │  :10071      │ │ service  │ │   :10073      │
     └──────────────┘ │ :10072   │ └───────────────┘
                      └──────────┘
```

## 模块清单

| 模块 | 端口 | 说明 |
|------|------|------|
| `gateway-server` | 8080 | Spring Cloud Gateway，5 套 profile，共享 Java 代码 |
| `user-service` | 10071 | 用户服务，提供 CRUD 接口 |
| `order-service` | 10072 | 订单服务，Feign 调用 user-service |
| `auth-service` | 10073 | 认证服务，JWT 签发/校验/登出 |

所有模块注册到 Nacos，通过 OpenFeign + LoadBalancer 进行服务间调用。

---

## 场景 1 — 基础路由与负载均衡

### 目标

掌握 Gateway 最核心能力——通过 Nacos 服务发现将外部请求路由到内部微服务。

### 配置要点

- `spring.cloud.gateway.routes` 定义路由规则
- 通过 `lb://service-name` 启用 Nacos 服务发现 + LoadBalancer 负载均衡
- Path 断言 + `StripPrefix` 路径改写：`/api/user/**` → 去掉 2 段前缀 → user-service 实际路径
- 全局 CORS 配置
- `spring.cloud.gateway.discovery.locator.enabled=true` 自动发现路由（演示用）

### 路由表

| 外部路径 | 目标服务 | 改写后路径 |
|----------|----------|-----------|
| `/api/user/**` | `lb://user-service` | `/**`（去掉 `/api/user`） |
| `/api/order/**` | `lb://order-service` | `/**`（去掉 `/api/order`） |
| `/api/auth/**` | `lb://auth-service` | `/**`（去掉 `/api/auth`） |

### 验证

```bash
curl http://localhost:8080/api/user/1     # → user-service /1
curl http://localhost:8080/api/order/101  # → order-service /101
```

### 涉及文件

- `gateway-server/pom.xml` — 引入 `spring-cloud-starter-gateway`
- `gateway-server/src/main/resources/application-scenario1.yml`
- `user-service/src/main/java/com/liepig/user/controller/UserController.java`
- `order-service/src/main/java/com/liepig/order/controller/OrderController.java`

---

## 场景 2 — 过滤器链与请求改写

### 目标

掌握 GatewayFilter 和 GlobalFilter 自定义，理解过滤器链生命周期。

### 核心内容

#### GlobalFilter（Ordered 排序）

- **LoggingFilter**（Order: -1）— 记录请求方法、URL、耗时、响应状态码
- **RequestHeaderFilter**（Order: 0）— 给所有下游请求统一注入 `X-Gateway-Id`、`X-Gateway-Timestamp`

#### GatewayFilterFactory

- **ValidateHeaderGatewayFilterFactory** — 校验请求必须携带 `X-Client-Version`，否则返回 400。通过配置绑定到指定路由

#### 内置过滤器

- `AddRequestHeader` / `AddResponseHeader` — 注入 `X-Gateway-Node`
- `RemoveResponseHeader` — 去除 `Server`、`X-Powered-By` 敏感头
- `ModifyRequestBody` / `ModifyResponseBody` — 请求/响应体简单改写示例

### 过滤器执行链路

```
客户端请求
  → LoggingFilter.pre（记录开始时间）
  → ValidateHeaderFilter（校验 X-Client-Version）
  → RequestHeaderFilter（注入统一头）
  → AddRequestHeader / AddResponseHeader
  → 路由到下游服务
  → RemoveResponseHeader（去除敏感头）
  → LoggingFilter.post（计算耗时，输出日志）
  → 响应客户端
```

### 验证

```bash
# 缺少必要头 → 400
curl http://localhost:8080/api/user/1

# 正常请求，观察响应头
curl -H "X-Client-Version:1.0" http://localhost:8080/api/user/1 -v
```

### 涉及文件

- `gateway-server/.../filter/LoggingGlobalFilter.java`
- `gateway-server/.../filter/RequestHeaderGlobalFilter.java`
- `gateway-server/.../filter/factory/ValidateHeaderGatewayFilterFactory.java`
- `application-scenario2.yml`

---

## 场景 3 — 安全认证

### 目标

掌握网关层统一认证——JWT 校验、Token 中继、IP 黑白名单、登出黑名单。

### 核心内容

#### auth-service 模块

- `POST /auth/login` — 用户名 + 密码校验通过后签发 JWT（HS256）
  - Payload: `{userId, username, roles, exp, iat}`
  - 返回: `{"token":"...", "expiresIn":3600}`
- `POST /auth/verify` — 校验 JWT 签名 + 过期，供下游 Feign 调用
- `POST /auth/logout` — 将 Token 写入 Redis 黑名单（TTL = Token 剩余有效期）

#### Gateway 层 AuthGlobalFilter（Order: -100，早于业务 filter）

- 从 `Authorization: Bearer <token>` 提取 JWT
- 校验签名 + 过期时间
- 检查 Redis 黑名单（已登出的 token 直接拒绝）
- 解析后将 `userId`、`roles` 写入 `X-User-Id`、`X-User-Roles` 头，中继到下游
- 白名单路径（`/auth/login`、`/public/**`）跳过校验
- Token 无效 → 401，黑名单 → 401

#### IP 黑白名单

- 使用 `RemoteAddr` 路由断言，`/admin/**` 仅允许 `127.0.0.1`、`192.168.0.0/16`
- 外网访问 `/admin/**` 返回 403

### 验证

```bash
# 登录获取 token
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type:application/json" \
  -d '{"username":"admin","password":"123456"}'

# 无 token → 401
curl http://localhost:8080/api/user/1

# 带 token → 200
curl -H "Authorization:Bearer <token>" http://localhost:8080/api/user/1

# 登出后 token 失效
curl -X POST -H "Authorization:Bearer <token>" http://localhost:8080/auth/logout
curl -H "Authorization:Bearer <token>" http://localhost:8080/api/user/1  # → 401

# 外网访问 admin → 403
curl http://<公网IP>:8080/admin/health
```

### 涉及文件

- `auth-service/` 模块（Spring Boot + JWT 工具类）
- `gateway-server/.../filter/AuthGlobalFilter.java`
- `application-scenario3.yml`

---

## 场景 4 — 流量控制与弹性

### 目标

掌握网关层限流、熔断、重试——保护下游服务。

### 核心内容

#### Redis 分布式限流

- 使用 `RequestRateLimiterGatewayFilterFactory` + `redis-rate-limiter`
- 用户维度: `KeyResolver` 解析 `X-User-Id`，默认 5 次/秒（模拟 API 配额）
- IP 维度（未认证用户）: 默认 10 次/秒（防刷）
- 超限返回 `429 Too Many Requests` + `Retry-After: 1` 头

#### 熔断降级（Spring Cloud CircuitBreaker + Resilience4J）

- 为 `order-service` 路由配置 `CircuitBreaker` filter
- 触发条件: 滑动窗口 10 次请求中 ≥50% 失败 → 熔断打开
- 半开试探: 5 秒后放少量请求，成功 → 关闭；失败 → 继续打开
- Fallback: 熔断时走 `/fallback/order-service` 返回降级 JSON

#### 重试策略

- `Retry` GatewayFilter: 连接超时或 5xx 时重试 3 次，间隔 500ms/1s/2s 退避
- 仅对 POST/PUT 写操作路由配置重试

#### 超时控制

- 全局: 连接超时 3s，读超时 5s
- `order-service` 路由覆盖: 连接超时 2s，读超时 8s

### 验证

```bash
# 快速刷 15 次 → 后几次 429
for i in {1..15}; do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -H "Authorization:Bearer $TOKEN" http://localhost:8080/api/user/1
done

# 停掉 order-service → 熔断后返回降级 JSON
curl http://localhost:8080/api/order/1  # → {"code":503,"msg":"订单服务繁忙"}
```

### 涉及文件

- `gateway-server/.../config/GatewayRateLimiterConfig.java`
- `gateway-server/.../controller/FallbackController.java`
- `application-scenario4.yml`

---

## 场景 5 — 全链路可观测

### 目标

掌握网关层 Trace、Metrics、Log 三大可观测支柱。

### 核心内容

#### TraceId 生成与传递

- **网关入口**: `TraceIdGlobalFilter` 为每个请求生成 `X-Trace-Id`（`<timestamp>-<uuid[0:8]>`
- **下游服务 Servlet Filter**: 各服务通过 `OncePerRequestFilter` 从请求头提取 traceId 放入 MDC
- **Feign 传递**: `FeignRequestInterceptor`（`@RequestHeader` 自动携带）
- **日志格式**: 所有模块 SLF4J MDC 配置 `%d{ISO8601} [%thread] %-5level [traceId:%X{traceId}] %logger{36} - %msg%n`

#### Micrometer + Actuator 指标暴露

- `/actuator/gateway/routes` — 路由列表及状态
- `/actuator/metrics/spring.cloud.gateway.requests` — 请求总量
- `/actuator/metrics/http.server.requests` — 各路由耗时分布（P50/P95/P99）
- `/actuator/health` — 复合健康检查（Redis、Nacos、下游服务）

#### 结构化访问日志

- `AccessLogFilter` 每次请求结束输出一行 JSON: `{"traceId":"...","method":"GET","path":"/api/user/1","status":200,"durationMs":42,"upstream":"user-service"}`

#### 实时统计接口

- `GET /gateway/admin/stats` — 返回各路由实时 QPS + 状态码分布（内存 `ConcurrentHashMap` 滑动窗口）

### 验证

```bash
# 查看网关指标
curl http://localhost:8080/actuator/metrics/spring.cloud.gateway.requests

# 检查 traceId 贯穿全链路
# 发一个请求，在 gateway/user/order 日志中搜索同一个 traceId

# 查看实时统计
curl http://localhost:8080/gateway/admin/stats
```

### 涉及文件

- `gateway-server/.../filter/TraceIdGlobalFilter.java`
- `gateway-server/.../filter/AccessLogFilter.java`
- 各服务 `.../filter/TraceIdServletFilter.java`
- 各服务 `.../config/FeignTraceInterceptor.java`
- 各模块 `logback-spring.xml`
- `application-scenario5.yml`

---

## 依赖关系

### Maven 依赖

**gateway-server:**
```xml
spring-cloud-starter-gateway
spring-cloud-starter-loadbalancer
spring-cloud-starter-circuitbreaker-reactor-resilience4j
spring-cloud-starter-alibaba-nacos-discovery
spring-boot-starter-data-redis-reactive
spring-boot-starter-actuator
micrometer-registry-prometheus  (可选)
jjwt-api / jjwt-impl / jjwt-jackson  (场景3+)
```

**user-service / order-service / auth-service:**
```xml
spring-boot-starter-web
spring-cloud-starter-alibaba-nacos-discovery
spring-cloud-starter-openfeign
spring-cloud-starter-loadbalancer
spring-boot-starter-actuator  (场景5)
```

### 配置 Profile 递进关系

```
scenario1.yml    基础路由 + CORS
scenario2.yml    = scenario1 + Filter 配置 + 敏感头处理
scenario3.yml    = scenario2 + Auth Filter + IP 黑白名单 + Redis Token 黑名单
scenario4.yml    = scenario3 + 限流 + 熔断 Fallback + 重试 + 超时
scenario5.yml    = scenario4 + Actuator 全开 + Trace + Metrics + 结构化日志
```

每个 YAML 文件独立完整，使用 Spring Boot 多文档语法（`---` 分段）组织：文件开头声明 `spring.profiles: scenarioN`，后续分段按需覆盖。部分公共配置（如 Nacos 地址、应用名）抽取到 `application.yml`，profile 文件不重复声明。`spring.profiles.include` 在此场景不适用（5 个 profile 各自独立启动，非叠加关系）。

### 启动方式

```bash
# 场景 1
java -jar gateway-server.jar --spring.profiles.active=scenario1

# 场景 5（全功能）
java -jar gateway-server.jar --spring.profiles.active=scenario5
```

---

## 项目结构

```
spring-cloud-2025/
├── pom.xml                                        # 父 POM，依赖管理
├── gateway-server/
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/liepig/gateway/
│       │   ├── GatewayApplication.java
│       │   ├── config/
│       │   │   ├── CorsConfig.java                # 场景1
│       │   │   ├── GatewayRateLimiterConfig.java   # 场景4
│       │   │   └── RouteConfig.java               # 场景1-3（Java DSL 路由）
│       │   ├── controller/
│       │   │   ├── FallbackController.java         # 场景4
│       │   │   └── StatsController.java            # 场景5
│       │   ├── filter/
│       │   │   ├── LoggingGlobalFilter.java        # 场景2
│       │   │   ├── RequestHeaderGlobalFilter.java   # 场景2
│       │   │   ├── AuthGlobalFilter.java           # 场景3
│       │   │   ├── TraceIdGlobalFilter.java        # 场景5
│       │   │   └── AccessLogFilter.java            # 场景5
│       │   └── filter/factory/
│       │       └── ValidateHeaderGatewayFilterFactory.java  # 场景2
│       └── resources/
│           ├── application.yml                     # 公共配置
│           ├── application-scenario1.yml
│           ├── application-scenario2.yml
│           ├── application-scenario3.yml
│           ├── application-scenario4.yml
│           ├── application-scenario5.yml
│           └── logback-spring.xml                  # 场景5 MDC 配置
├── user-service/
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/liepig/user/
│       │   ├── UserServiceApplication.java
│       │   ├── controller/UserController.java
│       │   ├── filter/TraceIdServletFilter.java    # 场景5
│       │   └── config/FeignTraceInterceptor.java   # 场景5
│       └── resources/
│           ├── application.yml
│           └── logback-spring.xml                  # 场景5
├── order-service/
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/liepig/order/
│       │   ├── OrderServiceApplication.java
│       │   ├── controller/OrderController.java
│       │   ├── feign/UserFeignClient.java
│       │   ├── filter/TraceIdServletFilter.java    # 场景5
│       │   └── config/FeignTraceInterceptor.java   # 场景5
│       └── resources/
│           ├── application.yml
│           └── logback-spring.xml                  # 场景5
└── auth-service/
    ├── pom.xml
    └── src/main/
        ├── java/com/liepig/auth/
        │   ├── AuthServiceApplication.java
        │   ├── controller/AuthController.java
        │   ├── util/JwtUtil.java
        │   ├── model/LoginRequest.java
        │   ├── filter/TraceIdServletFilter.java    # 场景5
        │   └── config/FeignTraceInterceptor.java   # 场景5
        └── resources/
            ├── application.yml
            └── logback-spring.xml                  # 场景5
```

---

## 测试策略

- **单元测试**: 各 GatewayFilter 和 GatewayFilterFactory 的可配置单元测试
- **集成测试**: 通过 `@SpringBootTest(webEnvironment=RANDOM_PORT)` + Mock 下游服务验证路由行为
- **手动验证**: 每个场景附带 curl 命令脚本，启动后逐场景验证

## 已知限制

- 场景 4 的 Redis 限流依赖本地 Redis 实例，未启动 Redis 时限流配置降级为内存实现
- 场景 5 的 Prometheus 指标采集为可选，不引入额外采集器
- JWT 使用对称密钥 HS256，简化演示（生产建议 RS256）
