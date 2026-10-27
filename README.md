# 苍穹外卖 · 单体的服务化改造

把一个 Spring Boot 单体（教学项目「苍穹外卖」）改造成 **4 模块聚合工程 + 2 个可独立部署的服务**，
并在此基础上补齐了服务发现、声明式远程调用、限流、消息队列这几块「微服务必备但单体没有」的能力。

> **关于措辞**：这个项目我称为「**单体的服务化改造**」或「**轻量级微服务实践**」，
> 而不是「微服务架构」。因为完整的微服务架构通常还要求独立数据源、网关、配置中心、
> 链路追踪、分布式事务、独立部署流水线等，本项目出于收益考虑**故意没有做**（见文末「已知限制」）。
> 我认为把改造边界说清楚，比堆砌名词更有意义。

---

## 一、改造前后

| 维度 | 改造前（单体） | 改造后 |
|---|---|---|
| 工程结构 | 单 Maven 模块 | **4 模块聚合**（common / pojo / server / ai-service） |
| 可部署单元 | 1 个 | **2 个**（sky-server 8080、ai-service 8081），可分别启停、独立扩容 |
| 服务发现 | 无，AI 服务里硬编码 `localhost:8080` | **Nacos** 注册中心，服务名寻址 |
| 服务调用 | 手写 `HttpClient` + 拼 URL | **OpenFeign** 声明式接口 + 负载均衡 |
| 流量防护 | 无 | **Sentinel** 限流（全局 5 QPS + 按 userId 1 QPS） |
| 超时订单处理 | `@Scheduled` 定时扫表 | **RabbitMQ TTL + 死信队列**（到点投递，数据库零轮询） |
| 接口规模 | — | 100 个接口方法 / 171 个类 / 约 1 万行（仅 `src/main/java`） |

---

## 二、模块结构

| 模块 | 职责 | 规模 |
|---|---|---|
| `sky-common` | 工具类、常量、异常、JWT、上下文 | 29 类 / 977 行 |
| `sky-pojo` | 实体、DTO、VO（被两个服务共用） | 52 类 / 1560 行 |
| `sky-server` | 主业务服务：员工/菜品/套餐/订单/购物车/支付/统计 | 65 类 / 5517 行 / 84 接口 |
| `ai-service` | AI 客服服务：SSE 流式对话、工具调用、RAG 知识库 | 25 类 / 1915 行 / 16 接口 |

拆分的依据是**变更频率和资源画像**，不是「一个表一个服务」：

- `ai-service` 会调大模型，一次请求耗时秒级、吃内存、还可能被限流，和订单交易这类
  毫秒级接口混在一个 JVM 里，互相拖累（GC、线程池、连接池都是共享的），所以必须拆出去独立部署；
- `common`/`pojo` 是被共享的契约，抽成独立模块后，两个服务引同一份版本，改字段不会两边不一致。

---

## 三、架构与调用链

```
                        ┌──────────────┐
   小程序 / 商家端 Web ──▶│  sky-server  │  8080  服务名 sky-take-out
                        │  (主业务)     │
                        └──┬───┬───┬───┘
                           │   │   │
              ┌────────────┘   │   └──────────────┐
              ▼                ▼                  ▼
        ┌──────────┐    ┌───────────┐      ┌──────────────┐
        │  MySQL   │    │   Redis   │      │  RabbitMQ    │
        │ sky_take │    │  db0 缓存 │      │ 延迟队列+死信 │
        └──────────┘    └───────────┘      └──────────────┘
                           ▲
                           │ db1（会话记忆）
                        ┌──┴───────────┐
   AI 自测页 / 前端 ───▶│  ai-service  │  8081  服务名 ai-customer-service
                        │  (AI 客服)    │
                        └──────┬───────┘
                               │ OpenFeign（服务名寻址，无硬编码 IP）
                               └──────────▶ sky-server

       两者都向 Nacos（8848）注册；ai-service 接入 Sentinel（控制台 8858）
```

**订单超时取消的完整链路**（本项目最值得讲的一条）：

```
用户下单 → submitOrder 事务提交
   └─ afterCommit 投递（避免消息先到、数据未落库）
        └─ order.delay.exchange ──▶ order.delay.queue（消息 TTL = 15 分钟）
              └─ 到期变死信 ──▶ order.cancel.exchange ──▶ order.cancel.queue
                    └─ 消费者：先查订单状态做幂等，仅「待支付」才取消
```

---

## 四、技术栈

| 分类 | 选型 |
|---|---|
| 语言 / 运行时 | Java 17 |
| 基础框架 | Spring Boot **3.5.5** |
| 微服务 | Spring Cloud **2025.0.0** + Spring Cloud Alibaba **2025.0.0.0** |
| 注册中心 | Nacos 2.x（standalone） |
| 远程调用 | OpenFeign + Spring Cloud LoadBalancer |
| 流量防护 | Sentinel **1.8.9**（客户端与控制台同版本） |
| 消息队列 | RabbitMQ **3.13.7**（TTL + 死信队列实现延迟消息） |
| 数据访问 | MyBatis 3.0.3 + Druid + PageHelper |
| 存储 | MySQL **8.0.34**、Redis（主项目 db0 / AI 服务 db1） |
| AI | Spring AI Alibaba 1.1.8 + DashScope（通义千问）、SSE 流式输出、Function Calling、RAG |
| 文档 | Knife4j（OpenAPI 3） |

> 版本一律由父 POM 的 `dependencyManagement` 统一管理。注意 SCA BOM 会覆盖部分自有依赖版本，
> 所以自有依赖声明必须排在 BOM 之前（Maven 是「先声明者生效」），这一点在 `pom.xml` 里有注释说明。

---

## 五、关键实现

### 5.1 Nacos 注册发现 + OpenFeign

`ai-service` 调主项目不再写死 `http://localhost:8080`，而是声明接口：

```java
@FeignClient(name = "sky-take-out", contextId = "waimaiUser")
public interface WaimaiUserClient {
    @PostMapping("/user/user/innerLogin")
    Result<Map<String, Object>> innerLogin(@RequestBody Map<String, Object> body);
    // ...
}
```

- 服务名由 Nacos 解析成实例地址，主项目换端口 / 多实例对调用方透明；
- **同一个 `name` 声明多个 Feign 客户端必须给不同 `contextId`**，否则启动直接报 duplicate bean；
- 主项目侧对应加了 `/user/user/innerLogin`（内部登录）和 `/user/order/innerDetailByNumber/{number}`
  这类「服务间专用」接口，并在拦截器里放行，避免内部调用被用户 token 拦掉。

### 5.2 Sentinel 限流（按 userId 区分）

`ai-service` 里的三条关键改动：

| 文件 | 作用 |
|---|---|
| `config/SentinelRuleConfig.java` | 实现 `RequestOriginParser`，从 `token`（URL 参数或 `Authorization: Bearer`）解析出 `userId` 作为 origin；`@PostConstruct` 里用代码加载规则 |
| `config/AiBlockExceptionHandler.java` | 被限流时返回 **429** + 友好 JSON，而不是默认的英文错误页 |
| `application.yml` | `sentinel.transport.dashboard=localhost:8858`、`port=8719`、`eager=true` |

规则：全局 `/ai/generateStream` **5 QPS**，`userId=1` 单独 **1 QPS**。

踩过的坑（都写在代码注释里了）：

- 适配器包名是 `com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.*`，
  **不是** `...webmvc.callback.*`（Spring Boot 3 换了包）；
- `BlockExceptionHandler.handle` 有 4 个参数，中间多一个 `String resourceName`；
- 必须用 `getOutputStream()` 写字节，**不能用 `getWriter()`** —— SSE 已经取过输出流，
  再拿 Writer 会抛 `IllegalStateException`；写之前还要判断 `isCommitted()`。

### 5.3 RabbitMQ 延迟消息取消超时订单

用 **TTL + 死信队列** 实现延迟，不用定时任务扫表：轮询最快也要等一个扫描周期，
订单量大时还是全表范围的条件查询；MQ 是「到点才投递」，延迟更短、数据库零轮询。

| 文件 | 作用 |
|---|---|
| `config/RabbitMQConfiguration.java` | 声明 `order.delay.queue`（带死信参数）→ `order.cancel.queue` 的拓扑 |
| `mq/OrderTimeoutProducer.java` | 发消息，只带 orderId，**TTL 按消息设置** |
| `mq/OrderTimeoutConsumer.java` | 消费；**先查状态做幂等**，非「待支付」直接跳过 |
| `service/impl/OrderServiceImpl#submitOrder` | 用 `TransactionSynchronizationManager` 在 **afterCommit** 投递 |
| `task/OrderTask#processTimeoutOrder` | `@Scheduled` **已停用**（方法保留作兜底） |

**一个刻意做对的细节**：TTL 不配在队列上，而是每条消息单独 `setExpiration()`。

- 队列级 `x-message-ttl` 是建队列时写死的参数，之后只要改动超时时长就会报
  `406 PRECONDITION_FAILED - inequivalent arg 'x-message-ttl'`，必须手工删队列才能恢复；
- 改成消息级 TTL 后，队列参数固定，改配置（`sky.order.timeout-minutes`）不再需要删队列；
- 代价是 per-message TTL 只有消息到达队头时才判断过期 —— 若同队列混用长短不同的 TTL，
  长 TTL 会挡住后面的短 TTL。本项目所有订单超时时长一致，所以没有这个影响；
  真要支持多档延迟，再考虑延迟插件或按档位分队列。

### 5.4 AI 客服服务（`ai-service`）

- **SSE 流式**：`/ai/generateStream` 用 WebFlux `Flux` 逐段吐出，前端边收边渲染；
- **工具调用（Function Calling）**：`OrderQueryFunction` / `BusinessFunction` / `KnowledgeBaseFunction`
  注册为模型可调用的函数，模型自己决定「查订单 / 查商品 / 查知识库」；
- **后端兜底取数**：命中「我的订单」这类意图时，后端直接查真实数据注入提示词，
  不依赖模型「自觉」调工具，避免编造订单信息；
- **会话记忆**：聊天记录存 Redis（db1），按 sessionId 拼上下文；
- **鉴权**：`TokenInterceptor` 从 token 解析 userId 存入 `UserContext`，SSE 场景下 token 走 URL 参数。

---

## 六、压测

### 6.1 环境与口径（重要，避免误读）

- **本机压测**：压测客户端、sky-server、MySQL、Redis 全在同一台 Windows 机器（localhost），
  没有网络延迟、没有 Nginx，**单实例**、默认 JVM 参数、默认 Tomcat/HikariCP 配置；
- 压测工具是自己写的多线程 HTTP 压测脚本（`bench/loadtest.py`，keep-alive + 统计 P50/P95/P99，
  统计口径可控），不是 JMeter；
- 客户端是 **Python 单进程多线程（有 GIL）**，并发到 50 以上时**客户端先饱和**（QPS 曲线走平），
  所以下表数字应当理解为「**服务端吞吐 ≥ 该值**」的地板值，不是服务端极限。

### 6.2 结果（sky-server 8080，15 秒/轮）

| 场景 | 并发 | 总请求 | QPS | P50 | P95 | P99 | 成功率 |
|---|---|---|---|---|---|---|---|
| `/user/dish/list`（Redis 缓存命中） | 10 | 75,416 | 7,539 | 1.2 ms | 2.2 ms | 3.4 ms | 100% |
| `/user/dish/list`（Redis 缓存命中） | 50 | 110,441 | **7,358** | 6.2 ms | **13.4 ms** | 18.0 ms | 100% |
| `/user/dish/list`（Redis 缓存命中） | 100 | 105,300 | 7,012 | 12.9 ms | 28.6 ms | 38.4 ms | 100% |
| `/user/dish/dish/{id}`（**不走缓存**，直查 MySQL + 口味表） | 20 | 67,329 | 5,608 | 3.3 ms | 6.0 ms | 10.3 ms | 100% |
| 同上（**同并发横向对比**） | 50 | 83,999 | **5,595** | 8.0 ms | **17.7 ms** | 25.0 ms | 100% |

**缓存收益（同为 50 并发）**：QPS **7,358 vs 5,595（+31%）**，P95 **13.4 ms vs 17.7 ms（-24%）**。

> 口径说明：无缓存分支每请求还多打一行日志、多一次口味表查询，
> 所以这 31% 是「缓存 + 少两次 IO」的综合收益，不宜单独归因给缓存。
> 三轮压测共约 **29 万次请求，0 报错、0 超时**。

### 6.3 限流验证（`ai-service` 8081）

5 个请求**同时**打到 `/ai/generateStream`（token 对应 `userId=1`，规则为 1 QPS）：

```
状态码分布: {'200': 1, '429': 4}
429 body:   {"code":0,"msg":"你问得太快了，歇一秒再试～","data":null}
```

### 6.4 复现

```bash
# 1) 先拿一个用户 token（内部登录接口，仅供本地自测）
TOKEN=$(curl -s -X POST http://localhost:8080/user/user/innerLogin \
        -H 'Content-Type: application/json' -d '{"userId":1}' \
        | sed -E 's/.*"token":"([^"]+)".*/\1/')

# 2) 读接口压测：并发 50，持续 15 秒
python bench/loadtest.py \
  --url "http://localhost:8080/user/dish/list?categoryId=21" \
  --concurrency 50 --duration 15 --header "authentication=$TOKEN"

# 3) 限流验证：5 个请求同时打 AI 的 SSE 接口（会真实调用大模型）
AI_TOKEN=$(curl -s "http://localhost:8081/ai/login?userId=1" | sed -E 's/.*"token":"([^"]+)".*/\1/')
python bench/sentinel_test.py \
  --url "http://localhost:8081/ai/generateStream?message=hi" --token "$AI_TOKEN" -n 5
```

---

## 七、本地启动

### 7.1 依赖环境

| 组件 | 说明 |
|---|---|
| JDK | 17 |
| Maven | 3.8+（本机可离线编译：`mvn -o -pl sky-server -am compile`） |
| MySQL | 8.0，库名 `sky_take_out`（需先导入表结构与初始数据） |
| Redis | 默认 6379，主项目用 db0，AI 服务用 db1 |
| RabbitMQ | 3.13.x，5672 / 管理台 15672 |
| Nacos | 2.x，`startup.cmd -m standalone`，8848，未开鉴权 |
| Sentinel 控制台 | 可选，仅需看规则时启动：`java -Dauth.enabled=false -jar sentinel-dashboard-1.8.9.jar`（8858） |

### 7.2 配置密钥（必做）

真实密钥**不进仓库**，全部外置到被 `.gitignore` 忽略的 `application-local.yml`：

```bash
# 两个模块各一份，从模板复制后填自己的值
cp ai-service/src/main/resources/application-local.example.yml  ai-service/src/main/resources/application-local.yml
cp sky-server/src/main/resources/application-local.example.yml  sky-server/src/main/resources/application-local.yml
```

| 模块 | 需要填的项 |
|---|---|
| `ai-service` | `spring.ai.dashscope.api-key` |
| `sky-server` | `sky.datasource.password`、`sky.wechat.secret`、`sky.file.upload.path` |

`application.yml` 里只剩占位符（`${DASHSCOPE_API_KEY:}` 等），并通过
`spring.config.import: optional:classpath:application-local.yml` 引入本地文件。
**服务起不来的第一嫌疑就是这份文件缺失**：表现为 DashScope key 为空或数据库密码错误。

### 7.3 启动顺序

```
Nacos → RabbitMQ → sky-server(8080) → ai-service(8081) →（需要看规则时）Sentinel 控制台
```

两个服务直接跑各自的 `*Application` 主类即可（`SkyApplication` / `AiCustomerServiceApplication`）。

### 7.4 验证是否正常

| 检查 | 方法 |
|---|---|
| 注册成功 | `http://localhost:8848/nacos` → 服务列表应有 `sky-take-out`、`ai-customer-service` |
| 主业务 | `http://localhost:8080/doc.html`（Knife4j）随便调一个查询接口 |
| AI 客服 | 打开 `http://localhost:8081/index.html`，问「查我的订单」，能返回**真实订单**即通过 |
| MQ 拓扑 | `http://localhost:15672`（guest/guest）→ `order.delay.queue` 参数里应**没有** `x-message-ttl` |

演示超时取消：把 `application-dev.yml` 的 `sky.order.timeout-minutes` 改成 `1`，
重启后下单，约 1 分钟后订单状态变为 `6`（已取消），`cancelReason = "超时未支付"`。
（改成消息级 TTL 后，改这个值**不需要**再删队列。）

---

## 八、目录结构

```
new-sky-take-out-cloud/
├── pom.xml                      # 父 POM：版本管理（自有依赖须排在 BOM 之前）
├── sky-common/                  # 工具、常量、JWT、上下文、异常
├── sky-pojo/                    # entity / dto / vo（两个服务共用）
├── sky-server/                  # 主业务服务（8080）
│   ├── config/                  # Nacos、RabbitMQ 拓扑、Redis、WebMvc、Knife4j
│   ├── controller/{admin,user}/ # 商家端 / 用户端接口
│   ├── mq/                      # 延迟消息生产者、消费者
│   ├── service/ mapper/ task/   # 业务、持久层、定时任务（超时任务已停用）
│   └── resources/               # application*.yml（真实密钥在 application-local.yml）
├── ai-service/                  # AI 客服服务（8081）
│   ├── client/                  # OpenFeign：WaimaiUserClient / WaimaiAdminClient
│   ├── config/                  # Sentinel 规则与限流处理、Spring AI、Redis、RAG 初始化
│   ├── controller/ChatController.java
│   ├── function/                # Function Calling：订单查询 / 业务 / 知识库
│   ├── service/                 # ChatService（会话记忆）、RagService
│   ├── interceptor/             # token → userId
│   └── resources/static/index.html   # AI 自测页
└── bench/                       # 本机压测脚本（loadtest.py / sentinel_test.py）
```

---

## 九、已知限制与故意没做的事

**故意不做**（都权衡过，收益低于成本）：

| 候选 | 不做的理由 |
|---|---|
| Seata 分布式事务 | 两个服务共用同一个库，没有跨库事务，引入 Seata 是纯负担 |
| 分库分表 | 数据量远没到瓶颈，分片只会增加复杂度 |
| 网关 | 只有两个服务、两个入口，网关带来的价值不如多一跳延迟 |
| 链路追踪（SkyWalking） | 本机单机环境，日志 + 压测数据已够定位 |
| 继续拆服务 | 按变更频率拆到「AI / 主业务」这两层已经够，再拆是分布式单体 |
| 多实例 WebSocket 广播 | 自查后判断投入产出比低，主动跳过（`WebSocketTask` 为教学 demo，定时任务本就没启用） |

**已知问题（有意留到主线收尾后再处理，写在这里是为了不装作没有）**：

| 位置 | 问题 |
|---|---|
| `sky-server` `TokenInterceptor` | 存在 `debugUserId` 调试参数，可冒充任意用户 |
| `ai-service` `ChatController` | `/ai/login?userId=` 可越权换 token；SSE 把 token 放在 URL 上（会进访问日志） |
| `ai-service` `ChatController` | `/ai/businessHistory` 未鉴权 |
| `sky-server` `JwtTokenAdminInterceptor` | 明文打印 token |
| `ai-service` `ChatController` | `MAX_STREAM_CONTENT_LEN` 残留死代码 |
| 压测口径 | 数据仅代表本机单实例，客户端为 Python 多线程，非生产环境基准 |

---

## 十、说明

- 业务底座来自教学项目「苍穹外卖」，业务功能（员工/菜品/套餐/订单/支付/统计）保持原样；
  **本仓库的价值集中在服务化改造这部分**：模块拆分、注册发现、远程调用、限流、延迟消息。
- 前端不在本仓库：商家端 Web 在另一个工程（nginx `http://localhost`），小程序用微信开发者工具，
  AI 自测页随 `ai-service` 一起打包（`resources/static/index.html`）。
- 所有密钥均通过环境变量或 `application-local.yml` 注入，仓库内不含任何真实密钥。