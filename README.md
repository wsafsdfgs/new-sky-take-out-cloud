# 苍穹外卖服务化改造

基于教学项目「苍穹外卖」，在原 sky-common / sky-pojo / sky-server 三模块工程上新增独立的 ai-service 模块，
形成 4 个 Maven 模块的聚合工程与 2 个可独立部署的服务，并加入服务注册发现、声明式远程调用、接口限流和消息队列。

改造前的业务代码基本保留，改动集中在这部分。工程共 171 个类、约 1 万行 Java 代码、100 个接口方法。

## 功能

**主业务服务（sky-server，8080）**

员工管理、菜品与套餐管理、购物车、订单、支付回调、店铺营业状态、数据统计报表，均为原项目功能。

**AI 客服服务（ai-service，8081）**

SSE 流式对话接口，模型可调用订单查询、业务数据、知识库三类工具函数；知识库支持检索增强（RAG）；
会话记录存 Redis，按 sessionId 维护上下文。前端有两个入口：商家端经营助手接入商家端 Web（`/ai-assistant`），
面向用户的客服接入微信小程序端，调试使用微信开发者工具。

## 模块

| 模块 | 职责 | 规模 |
|---|---|---|
| `sky-common` | 工具类、常量、异常、JWT、线程上下文 | 29 类 / 977 行 |
| `sky-pojo` | 实体、DTO、VO，两个服务共用 | 52 类 / 1560 行 |
| `sky-server` | 主业务服务 | 65 类 / 5517 行 / 84 接口 |
| `ai-service` | AI 客服服务 | 25 类 / 1915 行 / 16 接口 |

新增 ai-service 的依据是资源画像。AI 服务调用大模型，单次请求耗时在秒级，占用内存较多，
并且存在被限流的情况，与订单交易这类毫秒级接口放在同一个 JVM 中会互相影响，
GC、线程池和数据库连接池都是共享的，因此独立部署。`common` 与 `pojo` 是原工程已有的公共模块，
两个服务共用一份版本，字段修改不会出现两边不一致。

## 架构

```
                     ┌──────────────┐
  小程序 / 商家端 ──▶│  sky-server  │ :8080   sky-take-out
                     │   主业务服务  │
                     └──┬───┬───┬───┘
                        │   │   │
             ┌──────────┘   │   └──────────────┐
             ▼              ▼                  ▼
       ┌──────────┐  ┌───────────┐     ┌──────────────┐
       │  MySQL   │  │   Redis   │     │  RabbitMQ    │
       │ 8.0.34   │  │    db0    │     │ 延迟队列/死信 │
       └──────────┘  └───────────┘     └──────────────┘
                          ▲
                          │ db1 会话记忆
                     ┌────┴─────────┐
  商家端经营助手  ──▶│  ai-service  │ :8081   ai-customer-service
  小程序 AI 客服  ──▶│  AI 客服服务  │
                     └──────┬───────┘
                            │ OpenFeign，按服务名寻址
                            └──────────────▶ sky-server

        两个服务均注册到 Nacos（:8848），ai-service 接入 Sentinel（控制台 :8858）
```

订单超时自动取消的链路：

```
下单 → 事务提交后投递消息
     → order.delay.exchange → order.delay.queue（消息 TTL 15 分钟）
     → 到期成为死信 → order.cancel.exchange → order.cancel.queue
     → 消费者查询订单状态，仅「待支付」订单执行取消
```

## 技术栈

| 分类 | 选型 |
|---|---|
| 语言 | Java 17 |
| 基础框架 | Spring Boot 3.5.5 |
| 微服务 | Spring Cloud 2025.0.0、Spring Cloud Alibaba 2025.0.0.0 |
| 注册中心 | Nacos 2.x |
| 远程调用 | OpenFeign、Spring Cloud LoadBalancer |
| 限流 | Sentinel 1.8.9 |
| 消息队列 | RabbitMQ 3.13.7 |
| 数据访问 | MyBatis 3.0.3、Druid、PageHelper |
| 存储 | MySQL 8.0.34、Redis |
| AI | Spring AI Alibaba 1.1.8、DashScope（通义千问）、Function Calling、RAG |
| 接口文档 | Knife4j（OpenAPI 3） |

依赖版本统一由父 POM 的 `dependencyManagement` 管理。由于 Spring Cloud Alibaba 的 BOM 会覆盖部分依赖版本，
自有依赖的声明位置需要排在 BOM 之前，Maven 中先声明者生效。

## 关键实现

### 服务发现与远程调用

ai-service 调用主项目不再写死地址，改为声明 Feign 接口：

```java
@FeignClient(name = "sky-take-out", contextId = "waimaiUser")
public interface WaimaiUserClient {

    @PostMapping("/user/user/innerLogin")
    Result<Map<String, Object>> innerLogin(@RequestBody Map<String, Object> body);

    @GetMapping("/user/order/innerDetailByNumber/{number}")
    Result<OrderDTO> innerDetailByNumber(@PathVariable("number") String number,
                                         @RequestHeader("authentication") String userToken);
}
```

服务名由 Nacos 解析为实例地址，主项目更换端口或增加实例对调用方透明。
同一个服务名下声明多个客户端时通过 `contextId` 区分，否则启动阶段会报重复 Bean。
主项目侧补充了 `innerLogin`、`innerDetailByNumber` 等服务间接口，并在拦截器中放行，避免内部调用被用户 token 校验拦截。

### 接口限流

ai-service 通过 `RequestOriginParser` 实现按用户维度限流：从 `token` 参数或 `Authorization` 头中解析 userId 作为 origin，
规则在 `@PostConstruct` 中加载。全局 `/ai/generateStream` 限制 5 QPS，userId 为 1 的调用方单独限制 1 QPS。
被限流的请求由 `BlockExceptionHandler` 返回 429 与 JSON 响应体。

Spring Boot 3 环境下适配器包名为 `com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback`，
`BlockExceptionHandler.handle` 方法有 4 个参数。响应需要写入 `getOutputStream()`，
SSE 接口已经获取过输出流，使用 `getWriter()` 会抛出 `IllegalStateException`，写入前还需判断 `isCommitted()`。

### 延迟消息

使用 RabbitMQ 的 TTL 与死信队列实现延迟投递，替代原有的定时任务扫表。
`OrderTimeoutProducer` 只发送 orderId，`OrderTimeoutConsumer` 消费前先查询订单状态，
非待支付状态直接返回，保证重复投递不会误取消已支付订单。消息在下单事务提交后投递，
通过 `TransactionSynchronizationManager` 挂在 afterCommit 回调中，避免消费者先于数据落库收到消息。

TTL 设置在消息上而不是队列上。队列级 `x-message-ttl` 是创建队列时固定的参数，
修改超时时长后与应用声明不一致会返回 `406 PRECONDITION_FAILED`，需要手动删除队列才能恢复；
改为按消息设置 `expiration` 后队列参数固定，调整 `sky.order.timeout-minutes` 不再影响已存在的队列。
代价是 per-message TTL 仅在消息到达队头时判断是否过期，同一队列混用不同 TTL 时较长的会阻塞较短的。
当前所有订单一律 15 分钟，不存在该情况。

### AI 客服

对话接口使用 WebFlux 的 `Flux` 返回 SSE 流。模型通过 Function Calling 调用
`OrderQueryFunction`、`BusinessFunction`、`KnowledgeBaseFunction` 三个函数，自行决定查询哪类数据。
为降低模型编造订单的概率，命中「我的订单」类意图时由后端直接查询订单数据并注入提示词，
不依赖模型主动调用工具。用户在接口层通过 token 解析，存入 `UserContext` 供工具函数使用。

## 压测

测试环境为本机单实例，压测客户端、服务与数据库运行在同一台 Windows 机器上，
未经过网络与 Nginx。服务使用默认 JVM 与 Tomcat 参数。压测工具为 `bench/loadtest.py`，
基于 HTTP keep-alive 的多线程客户端，统计请求耗时分布。

| 接口 | 并发 | 总请求 | QPS | P50 | P95 | P99 | 成功率 |
|---|---|---|---|---|---|---|---|
| `/user/dish/list`（Redis 缓存） | 10 | 75,416 | 7,539 | 1.2 ms | 2.2 ms | 3.4 ms | 100% |
| `/user/dish/list`（Redis 缓存） | 50 | 110,441 | 7,358 | 6.2 ms | 13.4 ms | 18.0 ms | 100% |
| `/user/dish/list`（Redis 缓存） | 100 | 105,300 | 7,012 | 12.9 ms | 28.6 ms | 38.4 ms | 100% |
| `/user/dish/{id}`（直查 MySQL） | 20 | 67,329 | 5,608 | 3.3 ms | 6.0 ms | 10.3 ms | 100% |
| `/user/dish/{id}`（直查 MySQL） | 50 | 83,999 | 5,595 | 8.0 ms | 17.7 ms | 25.0 ms | 100% |

同样 50 并发下，缓存命中的接口 QPS 比直查数据库高 31%，P95 低 24%。
无缓存分支每次请求多一次口味表查询和一条访问日志，这一差异包含缓存与减少两次 IO 的综合影响。
三轮压测共约 29 万次请求，无报错与超时。并发达到 50 以上时 QPS 曲线趋于平缓，
压测客户端为 Python 单进程多线程，此时瓶颈在客户端侧，服务端实际吞吐高于表中数值。

限流行为验证：5 个请求同时访问 `/ai/generateStream`（userId=1，规则为 1 QPS），结果 1 个返回 200，
4 个返回 429，响应体为 `{"code":0,"msg":"你问得太快了，歇一秒再试～","data":null}`。

复现命令：

```bash
# 获取用户 token（内部登录接口）
TOKEN=$(curl -s -X POST http://localhost:8080/user/user/innerLogin \
        -H 'Content-Type: application/json' -d '{"userId":1}' \
        | sed -E 's/.*"token":"([^"]+)".*/\1/')

# 压测读接口
python bench/loadtest.py \
  --url "http://localhost:8080/user/dish/list?categoryId=21" \
  --concurrency 50 --duration 15 --header "authentication=$TOKEN"

# 验证限流
AI_TOKEN=$(curl -s "http://localhost:8081/ai/login?userId=1" | sed -E 's/.*"token":"([^"]+)".*/\1/')
python bench/sentinel_test.py \
  --url "http://localhost:8081/ai/generateStream?message=hi" --token "$AI_TOKEN" -n 5
```

## 运行

**依赖**

| 组件 | 版本与说明 |
|---|---|
| JDK | 17 |
| Maven | 3.8 以上 |
| MySQL | 8.0，库名 `sky_take_out`，需导入建表脚本与初始数据 |
| Redis | 默认 6379，主项目使用 db0，AI 服务使用 db1 |
| RabbitMQ | 3.13.x，5672，管理台 15672 |
| Nacos | 2.x，单机模式启动，8848 |
| Sentinel 控制台 | 1.8.9，可选，8858，启动时加 `-Dauth.enabled=false` |

**配置**

数据库密码、微信 secret、DashScope API Key 等敏感配置不进入仓库，放在各模块的 `application-local.yml` 中，
该文件已被 `.gitignore` 忽略。首次运行从模板复制并填写：

```bash
cp ai-service/src/main/resources/application-local.example.yml ai-service/src/main/resources/application-local.yml
cp sky-server/src/main/resources/application-local.example.yml sky-server/src/main/resources/application-local.yml
```

| 模块 | 需要填写的配置项 |
|---|---|
| `ai-service` | `spring.ai.dashscope.api-key` |
| `sky-server` | `sky.datasource.password`、`sky.wechat.secret`、`sky.file.upload.path` |

`application.yml` 中保留 `${DASHSCOPE_API_KEY:}` 等占位符，通过
`spring.config.import: optional:classpath:application-local.yml` 引入本地文件。

**启动**

依次启动 Nacos、RabbitMQ、`SkyApplication`、`AiCustomerServiceApplication`。

**验证**

| 检查项 | 方式 |
|---|---|
| 服务注册 | 访问 `http://localhost:8848/nacos`，服务列表中有 `sky-take-out` 与 `ai-customer-service` |
| 主业务接口 | 访问 `http://localhost:8080/doc.html` 调用任意查询接口 |
| AI 客服 | 正式入口为商家端 Web 的 `/ai-assistant` 与微信小程序端；本地联调可临时使用服务自带的调试页 `http://localhost:8081/index.html` |

演示超时取消可将 `application-dev.yml` 中的 `sky.order.timeout-minutes` 改为 1，重启后下单，
一分钟后订单状态变为 6（已取消），取消原因为「超时未支付」。

## 目录结构

```
new-sky-take-out-cloud/
├── pom.xml                      父 POM，统一版本管理
├── sky-common/                  工具、常量、JWT、上下文
├── sky-pojo/                    实体、DTO、VO
├── sky-server/                  主业务服务
│   ├── config/                  Nacos、RabbitMQ 拓扑、Redis、WebMvc、Knife4j
│   ├── controller/admin/        商家端接口
│   ├── controller/user/         用户端接口
│   ├── mq/                      延迟消息生产者与消费者
│   ├── service/                 业务实现
│   ├── mapper/                  持久层
│   └── task/                    定时任务
├── ai-service/                  AI 客服服务
│   ├── client/                  OpenFeign 客户端
│   ├── config/                  Sentinel 规则、Spring AI、Redis、RAG 初始化
│   ├── controller/              对话接口
│   ├── function/                Function Calling 工具函数
│   ├── service/                 对话与会话记忆、RAG 检索
│   ├── interceptor/             token 解析
│   └── resources/static/        本地联调用的调试页
└── bench/                       压测脚本
```

## 说明

业务功能与前端来自教学项目「苍穹外卖」，前端不在本仓库：商家端 Web 为独立工程，小程序使用微信开发者工具。
本项目改动集中在新增独立服务、注册发现、远程调用、限流与延迟消息。
