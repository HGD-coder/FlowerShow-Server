# FlowerShow 服务端技术文档

> 版本：v1.0　|　适用项目：`flower-show-server`　|　技术栈：Spring Boot 3.3 + Java 17 + MyBatis + PostgreSQL

这份文档面向**想要快速理解这个后端项目的人**——包括新加入的开发者、需要联调的客户端同事，以及要接手维护的工程师。它会用"白话 + 图表 prompt + 代码定位"的方式，把整个项目讲清楚。

| 配套文档 | 内容 |
|---|---|
| `docs/flower-show-server-functional-requirements.md` | 功能需求（从业务视角讲"要做什么"） |
| `docs/auth-api.md` / `profile-social-api.md` / `social-feed-notification-api.md` / `recommendation-api.md` / `chat-api.md` | 各接口协议（从接口视角讲"怎么调"） |
| **本文档（技术文档）** | 架构与实现（从代码视角讲"怎么做的、为什么这样做、怎么改进"） |

---

## 目录

1. [项目概览](#1-项目概览)
2. [整体架构](#2-整体架构)
3. [模块划分与目录结构](#3-模块划分与目录结构)
4. [核心流程详解](#4-核心流程详解)
5. [功能模块详解](#5-功能模块详解)
6. [数据模型](#6-数据模型)
7. [配置与部署](#7-配置与部署)
8. [测试策略](#8-测试策略)
9. [改进建议](#9-改进建议)
10. [图表生成 Prompt 汇总](#10-图表生成-prompt-汇总)

---

## 1. 项目概览

### 1.1 这是什么项目？

**FlowerShow 是一个"花卉短视频 + 社区"类 App 的服务端**（类似"小红书/抖音但主题是花艺与植物"）。客户端是 Android App（Kotlin + Compose + Media3），服务端就是这个 Spring Boot 项目。

它负责的事情包括：

- **内容**：视频/图片/图集的元数据管理、发布、Feed 流、多清晰度播放地址
- **社区**：点赞、收藏、评论、关注、粉丝/关注列表、通知
- **推荐**：个性化推荐 Feed、搜索联想词、内容搜索（含语义向量召回）
- **实时聊天**：单聊/群聊 + WebSocket 实时消息
- **推送**：FCM 等厂商通道推送
- **数据导入**：把 MediaCrawler 爬取的抖音 JSONL 数据批量导入成平台内容
- **账号体系**：注册、登录、JWT + Refresh Token、作者账号生成

### 1.2 一句话架构

> 一个**经典的 Spring Boot 分层单体服务**（Controller → Service → Mapper → PostgreSQL），核心亮点是内置了一套**"Outbox 事件总线 + 关注流扇出 + 通知/推送"的异步闭环**，并自研了一个**"会话快照 + 防重 + 防作弊"的推荐服务**；聊天实时通道用**独立的 Netty WebSocket 端口**承载。

### 1.3 技术栈

| 类别 | 选型 | 用途 |
|---|---|---|
| 语言/框架 | Java 17 + Spring Boot 3.3.5 | 主框架 |
| Web | Spring MVC（`spring-boot-starter-web`） | REST API |
| 持久层 | MyBatis 3.0.5 + XML Mapper | 数据库访问 |
| 数据库 | PostgreSQL（测试用 H2） | 主存储；pgvector 扩展支持向量检索 |
| 数据库迁移 | Flyway（V1~V14 共 14 个版本） | 表结构版本管理 |
| 认证 | Spring Security + OAuth2 Resource Server + JWT (HS256) | 接口鉴权 |
| 消息 | Spring Kafka（可选开关） | 事件跨实例分发（默认关闭，用本地分发） |
| 实时通信 | Netty（自带 `netty-codec-http`） | 聊天 WebSocket 网关（独立端口 8090） |
| 推送 | Firebase Admin（FCM）+ 多厂商适配 | 离线推送 |
| 语义搜索 | Ollama（`qwen3-embedding`）+ pgvector | 向量召回（可选开关） |
| 测试 | JUnit 5 + Spring Boot Test + H2 | 单元/集成测试 |

### 1.4 代码规模

| 指标 | 数量 |
|---|---|
| 主代码 Java 文件 | 184 个（约 13,000 行） |
| 测试 Java 文件 | 22 个（约 4,700 行） |
| MyBatis Mapper XML | 21 个 |
| 数据库迁移脚本 | 15 个（V1~V13 通用 + V14 分 H2/PostgreSQL 两版） |

---

## 2. 整体架构

### 2.1 分层架构

项目采用标准的**三层架构**，但中间插入了一层"事件总线"，让部分业务"写完数据库后异步继续干活"。

```
┌─────────────────────────────────────────────────────────────────────┐
│                          HTTP 客户端 (Android App)                    │
└───────────────┬──────────────────────────────────────────┬───────────┘
                │ REST (8080)                                │ WebSocket (8090)
                ▼                                            ▼
┌───────────────────────────────┐              ┌──────────────────────┐
│         Controller 层          │              │    Netty WS 网关      │
│  controller / recommendation   │              │  chat/realtime/*     │
│  参数校验 + 鉴权 + 装配 DTO    │              │  JWT 握手 + 广播      │
└───────────────┬───────────────┘              └──────────┬───────────┘
                │ 调用                                       │ 调用
                ▼                                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│                          Service 层                                  │
│  service / chat / push / recommendation                             │
│  业务规则 + 事务边界 + 事件发布                                       │
└───────────────┬──────────────────────────────────────────┬───────────┘
                │ 读写                                        │ 发布领域事件
                ▼                                            ▼
┌───────────────────────────────┐              ┌──────────────────────┐
│    Repository / Mapper 层      │              │    Outbox 事件表      │
│  repository / persistence/*   │              │  event/outbox         │
│  MyBatis 接口 + XML           │              │  (同一本地事务写入)    │
└───────────────┬───────────────┘              └──────────┬───────────┘
                │                                          │ 后台任务扫描分发
                ▼                                          ▼
┌─────────────────────────────────────────────────────────────────────┐
│  PostgreSQL (业务表)              │  本地分发器 / Kafka（可选）        │
└──────────────────────────────────┘   → 生成通知、写入关注流、推送     │
```

**一句话理解**：Controller 只做"接单"（收参数、验身份、返回结构），Service 做"决策"（业务规则 + 事务），Mapper 做"存取"（SQL）。凡是不需要同步返回结果的后续动作（通知、关注流、推荐信号），都以**领域事件**的形式先写入本地的 `outbox_events` 表，再由后台任务慢慢消费——这样主流程既快又不丢事件。

### 2.2 外部依赖拓扑

生产/联调环境通过 `docker-compose.yml` 拉起一组依赖：

```
Android App
    │
    ▼
Cloudflare Tunnel (cloudflared) ──► Nginx (8088 网关)
                                        │
                  ┌─────────────────────┼─────────────────────┐
                  ▼                     ▼                     ▼
        Spring Boot (8080)      Netty WS (8090)        MinIO (9000)
        PostgreSQL (5432)                                    │ 媒体文件
        Kafka (9092, 可选)                                   ▼
        Ollama (11434, 语义搜索可选)                       Nginx /media 反代
```

- **Nginx** 是统一入口：反代 REST、反代聊天 WebSocket、反代 MinIO 媒体文件（支持 Range 断点、长缓存），并**直接屏蔽 `/api/v1/import/` 导入接口**（只能在本地访问）。
- **Cloudflare Tunnel** 提供公网访问（临时隧道或命名隧道），App 的 `gradle.properties` 里配置公网网关地址。
- **Kafka 默认关闭**，`start-flowershow.ps1` 脚本启动时通过环境变量开启。

### 2.3 架构特点与权衡

| 特点 | 说明 | 好处 | 代价 |
|---|---|---|---|
| 单体 + 事件解耦 | 一个应用，内部用 outbox 异步化 | 部署简单；同步链路快 | 单实例承载所有模块 |
| 自研推荐 | 会话快照 + 签名游标/曝光 token | 翻页稳定、防刷曝光 | 实现复杂度高 |
| 独立 WS 网关 | Netty 单独端口收实时消息 | 不影响 REST 线程池 | 多一套进程资源、鉴权需自行实现 |
| 可切换消息后端 | `LocalContentFanoutDispatcher` / `KafkaContentFanoutDispatcher` 按配置切换 | 本地单机到集群平滑演进 | 两套实现需同时维护 |

---

## 3. 模块划分与目录结构

### 3.1 包结构

源码根包：`com.github.hgdcoder.flowershow`

```
flowershow
├── FlowerShowServerApplication.java   # 启动类：@MapperScan + @EnableScheduling
│
├── controller/     # REST 入口（7 个）：Health / Auth / Content / Feed / Search /
│                   #   User / CurrentUser / Import / Account  + 顶层包中的 API
│   ├── HealthController          GET  /api/v1/health
│   ├── AuthController            POST /api/v1/auth/*
│   ├── ContentController         POST /contents、/uploads、/comments、/like、/favorite
│   ├── FeedController            GET  /feed、/feed/following、/videos
│   ├── SearchController          GET  /search
│   ├── UserController            /users/*（资料、关注、粉丝、通知）
│   ├── CurrentUserController     /me/*（本人资料、通知、设备）
│   ├── ImportController          POST /import/media-crawler-jsonl
│   └── AccountController         /accounts/authors（作者账号生成）
│
├── service/        # 业务服务（14 个）
│   ├── ContentService            内容查询、发布
│   ├── FollowingFeedService      关注流（游标分页）
│   ├── InteractionService        点赞/收藏/评论点赞
│   ├── CommentService            评论
│   ├── SocialService             关注/取关/粉丝列表/关注偏好
│   ├── NotificationService       通知列表/已读/未读数
│   ├── ProfileService            个人主页/资料编辑
│   ├── UserService / DeviceTokenService / AuthService / UploadService
│   ├── SearchService             简单搜索
│   ├── AuthorAccountService      作者账号生成
│   └── MediaCrawlerImportService 抖音 JSONL 导入
│
├── repository/     # 内容仓储（接口 + 两实现）
│   ├── ContentRepository         接口
│   ├── MyBatisContentRepository  生产实现（MyBatis）
│   └── InMemoryContentRepository 内存实现（早期/测试遗留）
│
├── persistence/    # 持久层：MyBatis Mapper 接口 + Row/Command 记录
│   ├── mapper/content/           content / media / feed / import
│   ├── mapper/social/            user / auth / comment / interaction / profile / social
│   ├── mapper/event/             outbox / fanout / notification / push
│   ├── mapper/chat/              conversation / message / member
│   ├── mapper/recommendation/    session / event / interest / embedding（含 vector TypeHandler）
│   └── resources/mapper/*.xml    对应 SQL（21 个 XML）
│
├── event/          # 事件驱动核心
│   ├── DomainEvent / EventTypes / EventPublisher   领域事件抽象
│   ├── outbox/     OutboxEventPublisher（事务内写表）→ OutboxEventDispatcher（定时扫表）
│   │               → LocalOutboxDispatchOperation（锁行处理）→ DomainEventProcessor
│   └── messaging/  FanoutPlanner / FanoutChunkProcessor（关注流扇出）
│                   LocalContentFanoutDispatcher / KafkaContentFanoutDispatcher
│                   NotificationWriter / ProcessedEventStore / KafkaEventListeners
│
├── recommendation/ # 推荐子系统（独立域）
│   ├── RecommendationController    POST /feed/pages、/search/guesses、/search/videos、/events:batch
│   ├── RecommendationService       会话快照 + 排序 + 幂等 + 游标
│   ├── RecommendationEventService  行为事件接收/去重/兴趣更新
│   ├── RecommendationTokenService  签名游标 & 曝光 token（HMAC）
│   ├── RecommendationRepository    推荐专用存取
│   ├── embedding/                  OllamaEmbeddingClient / EmbeddingIndexService / Worker
│   └── RecommendationExceptionHandler  推荐接口统一异常
│
├── chat/           # 聊天子系统
│   ├── ChatController / ChatService / ChatModels   REST + 业务
│   └── realtime/   ChatWebSocketGateway（Netty 服务端）、HandshakeHandler、
│                   FrameHandler、ChannelRegistry、EventBroadcaster
│
├── push/           # 推送子系统
│   ├── PushDeliveryWorker          定时扫描投递
│   ├── PushDeliveryStore           投递表存取/认领
│   ├── RoutingPushSender           按 provider 路由
│   ├── PushAdapter / PushProvider / FcmPushSender  适配器
│   └── PushErrorCode / PushSendResult / PushDeliveryMessage / PushDeliveryClaim
│
├── config/         # 配置类：Security / Web(CORS) / Kafka / JWT / Push / Embedding / ChatWS
├── security/       # AuthenticatedUser（从 JWT 取用户、校验身份工具）
├── model/          # DTO / 请求体 / 响应体（约 40 个 record）
└── util/           # CursorCodec（游标编解码）
```

### 3.2 模块职责总览

| 模块 | 关键类 | 核心职责 |
|---|---|---|
| Web 层 | `controller/*` | 参数校验、鉴权、请求→服务映射 |
| 认证 | `AuthService`, `SecurityConfig` | 注册/登录/刷新令牌、JWT 签发校验、接口放行规则 |
| 内容 | `ContentService`, `ContentRepository` | 内容 CRUD、Feed/视频列表、卡片 DTO 组装 |
| 社交 | `SocialService`, `InteractionService`, `CommentService` | 关注关系、点赞收藏、评论 |
| 关注流 | `FollowingFeedService`, `FanoutPlanner` | 写时扇出（push）、读时游标分页 |
| 通知 | `NotificationService`, `DomainEventProcessor`, `NotificationWriter` | 事件→通知、已读、未读计数 |
| 推荐 | `Recommendation*` | 会话化推荐、行为收集、兴趣建模、幂等/防作弊 |
| 语义 | `embedding/*` | 向量索引、相似度打分 |
| 聊天 | `chat/*` | 会话/消息 CRUD、WebSocket 实时推送 |
| 推送 | `push/*` | 通知投递队列、厂商适配、重试退避 |
| 导入 | `MediaCrawlerImportService` | 抖音 JSONL 解析、去重、落库 |
| 事件 | `event/*` | Outbox 事务写入 + 定时分发 + 幂等消费 |

---

## 4. 核心流程详解

### 4.1 一次请求的生命周期（以"点赞"为例）

```
App 请求 POST /api/v1/contents/{id}/like   +  JWT
   │
   ▼
① SecurityConfig 过滤器链
   ├─ 解析 JWT（HS256 + issuer/audience 校验）
   ├─ 从 claims 取出 subject → userId
   └─ 校验路径权限（该接口需登录）
   │
   ▼
② ContentController.likeContent()
   └─ AuthenticatedUser.requireUser(jwt, request.userId())
      └─ 确保"请求体里的 userId" == "JWT 里的 userId"（防伪造他人身份）
   │
   ▼
③ InteractionService.likeContent()
   ├─ assertContentExists / assertUserExists       （存在性校验）
   ├─ tryInsertContentLike → 防重复（靠主键冲突，重复返回 changed=false）
   ├─ incrementContentLikeCount                    （更新内容统计）
   └─ eventPublisher.publish(CONTENT_LIKED 事件)    ← 事务内写 outbox_events
   │
   ▼
④ 返回 { "changed": true, "stats": { likeCount: ... } }
   （HTTP 200，同步响应结束）
   │
   ▼
⑤ 【异步】OutboxEventDispatcher 定时扫到事件
   └─ LocalOutboxDispatchOperation 锁行 → DomainEventProcessor
      └─ 查内容作者 → NotificationWriter 生成"有人赞了你"通知
```

**关键点**：点赞的"同步返回"非常快，只做了写关系表 + 更新计数 + 写 outbox 三件事；生成通知是后台异步完成的。这就是"事务 + outbox"解耦的好处——**主流程不依赖通知是否成功**。

### 4.2 登录与令牌

```
POST /api/v1/auth/login { username, password }
   │
   ▼
AuthService.login
   ├─ 归一化用户名（小写）
   ├─ 查账号（不存在 → 走 dummy hash 校验，防用户枚举）
   ├─ 检查账号状态（active?）
   ├─ 检查锁定时间（连续失败 5 次 → 锁 15 分钟）
   ├─ BCrypt(12) 校验密码
   │    失败 → 记录失败次数，可能触发锁定
   │    成功 → 清零失败计数
   └─ 签发令牌对：
       ├─ Access Token ：JWT (HS256)，有效期 15m
       │     payload: sub=userId, accountId, username, roles
       └─ Refresh Token：随机 48 字节，服务端只存 SHA-256 哈希，有效期 30d
                          存 auth_refresh_tokens 表（支持轮换/撤销）
```

**刷新令牌轮换机制**：`POST /auth/refresh` 时，旧 token 被标记 `replaced_by` 指向新 token，用过的旧 token 立即失效；`logout` 按 token 撤销，`logout-all` 按账号全部撤销。这样即使 refresh token 泄露，被使用一次后也无法重放。

### 4.3 内容发布 → 关注流扇出（本项目最精彩的部分）

作者发布内容后，平台需要把这条内容"投递"到每个关注者的关注流里。项目采用**写时扇出（fanout on write）**：

```
① 作者 POST /api/v1/contents
   ▼
② ContentService.createContent
   ├─ 事务内：写入 content_items + media_assets + content_tags + content_stats
   └─ 事务内：发布 CONTENT_PUBLISHED 事件（payload 含 authorUserId）
   ▼
③ 【异步】Outbox 调度 → DomainEventProcessor
   └─ case CONTENT_PUBLISHED → contentFanoutDispatcher.dispatch(event)
   ▼
④ FanoutPlanner.plan()
   ├─ 查内容（确认 published + public）
   ├─ 判断扇出模式：
   │    push 模式            → 写 feed
   │    auto 模式 + 粉丝数 ≤ 阈值(10万) → 写 feed
   │    大 V（超过阈值）      → 不写 feed，读时由 FollowingFeedMapper 动态查询合并
   ├─ 分页查该作者的所有关注者（每批 chunkSize=1000）
   └─ 对每批生成一个 FanoutChunkMessage（含 SHA-256 指纹的 chunkId）
   ▼
⑤ FanoutChunkProcessor.process()  （REQUIRES_NEW 事务）
   ├─ 幂等检查（consumer_processed_events 表查 chunkId）
   ├─ 对每个关注者：
   │    addToFeed=true  → 写 user_feed_entries（userId, contentId, score=发布时间）
   │    notifyNewContent=true → 生成"作者更新了"通知
   └─ 记录已处理 chunkId
   ▼
⑥ 读者 GET /api/v1/feed/following?cursor=...
   └─ FollowingFeedService
       ├─ 查 user_feed_entries（游标分页，score desc, content_id desc）
       └─ 按 contentId 批量回填卡片信息
```

**设计亮点**：
- **写时扇出 + 读时合并的双保险**：大 V 不写粉丝的 feed 表，而是读取时实时 join——避免"百万粉丝 × 每条内容"的写放大。
- **幂等消费**：`consumer_processed_events(consumer_name, event_id)` 保证同一事件不会被处理两次；chunkId 含内容指纹，重试安全。
- **事务边界清晰**：扇出失败不会回滚内容发布（各自独立事务）。

### 4.4 互动 → 通知

点赞/收藏/评论/关注 → 发布对应领域事件 → `DomainEventProcessor` 按类型分发到 `NotificationWriter`：

```
事件类型                通知接收人          通知类型
COMMENT_CREATED         父评论作者/内容作者   comment
COMMENT_LIKED           评论作者             comment_like
CONTENT_LIKED           内容作者             like
CONTENT_FAVORITED       内容作者             favorite
USER_FOLLOWED           被关注者             follow
CONTENT_PUBLISHED       (关注流扇出，见 4.3)
CHAT_MESSAGE_CREATED    (实时广播，见 4.6)
```

`NotificationWriter` 做了三件事：插入通知（带 `dedupe_key = eventId:type` 防重）、更新 `notification_unread_stats` 未读计数、把该用户的所有设备 token 入队到 `notification_deliveries`（供推送模块使用）。

### 4.5 推荐系统

推荐接口是**独立的一套协议**（POST + 幂等键 + 会话 + 签名 token），与普通 REST 风格不同：

```
① App POST /api/v1/feed/pages
   { clientRequestId, serveSessionId, cursor, limit, scene, refresh }
   ▼
② RecommendationService.respondIdempotently
   ├─ 幂等键 = (actorKey, endpoint, clientRequestId)
   ├─ 若已处理过相同请求 → 直接重放存储的响应（连 HTTP 状态一起）
   └─ 否则 claim 排他后执行真实逻辑
   ▼
③ resolveSession
   ├─ refresh=true            → 重建快照
   ├─ cursor 非空             → 校验签名游标 + 快照一致性，取 offset
   ├─ serveSessionId 非空     → 复用该会话
   └─ 否则                    → 新建会话（生成不可变排序快照 Snapshot）
   ▼
④ feedSnapshot 打分排序（organic-hybrid-v3）
   每个视频得分 =
      0.48 × 热度(对数点赞/收藏/分享)
    + 0.25 × 新鲜度(时间衰减)
    + 0.55 × (1-e^{-兴趣分/5})       ← 用户行为兴趣
    + 0.18 × 语义相似度(向量，可选)
   再经 SeededSessionDiversifier 做确定性打散（作者重复惩罚）
   ▼
⑤ 分页切片 + 生成响应
   ├─ 每条 item 附带 signed exposure token（防刷曝光）
   └─ nextCursor = 签名游标（含 offset、会话 id、到期时间）
```

**防作弊设计**：客户端回传行为事件（`POST /events:batch`）时必须带服务端下发的 `exposureToken`，服务端校验该 token 与该次展示的会话快照、排名、实体一致，才接受事件。这样**伪造"我看过/我点赞了某个推荐内容"非常困难**，保障了行为数据可信度。

### 4.6 实时聊天

聊天是"REST 管数据 + WebSocket 管实时"的组合：

```
① 发送消息（REST）
   POST /api/v1/chat/conversations/{id}/messages
   └─ ChatService.send
       ├─ 校验成员身份/会话状态
       ├─ 写入 chat_messages（自增 sequence_no + client_message_id 幂等）
       └─ 发布 CHAT_MESSAGE_CREATED 事件
       ▼
② 实时广播（异步）
   DomainEventProcessor → broadcastChatMessage
   ├─ 查会话"在线成员"user_id 列表（按连接状态过滤）
   └─ ChatRealtimeEventBroadcaster 通过 Netty 通道推送给在线用户
   ▼
③ 长连接
   App 连 ws://host:8090/ws/chat（握手时带 JWT）
   ├─ ChatWebSocketHandshakeHandler 校验 JWT → 注册到 ChannelRegistry
   └─ 断线自动清理；服务端按 idle 发 ping 保活
```

**要点**：WebSocket 网关用**独立的 Netty 端口（8090）**，不占 Spring MVC 线程池；Nginx 单独为 `/ws/chat` 配置了 Upgrade 反代和超长 read timeout。群聊解散、转让等管理操作也会发事件，但目前只有 `CHAT_MESSAGE_CREATED` 走了实时广播。

### 4.7 推送

通知被创建后，投递队列在后台被逐步消化：

```
NotificationWriter 创建通知
   └─ 为该用户所有 enabled 设备 token 插入 notification_deliveries(pending)
       ▼
PushDeliveryWorker（@Scheduled，每 5s）
   ├─ deliveryStore.maintain：清理过期/超时认领
   ├─ claimBatch：批量认领一批投递（status→processing，带认领者/时间）
   └─ 逐个调 pushSender.send
       ├─ RoutingPushSender 按设备 provider（fcm/huawei/xiaomi/oppo/vivo）路由
       └─ FcmPushSender 调 Firebase
   ▼
结果处理
   ├─ 成功 → delivered
   ├─ 可重试失败 → retry（指数退避 5s~15m，最多 8 次 → dead）
   └─ INVALID_RECIPIENT → 该设备标记失效，同批跳过该设备后续消息
```

---

## 5. 功能模块详解

### 5.1 认证与安全模块

**相关文件**：`config/SecurityConfig.java`、`service/AuthService.java`、`security/AuthenticatedUser.java`、`config/JwtProperties.java`、`persistence/mapper/social/AuthMapper.java`

**接口**：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/auth/register` | 注册（用户名唯一、BCrypt 加密、自动建用户/账号/统计行） |
| POST | `/api/v1/auth/login` | 登录（失败锁定、成功签发令牌对） |
| POST | `/api/v1/auth/refresh` | 刷新令牌（轮换 + 旧令牌立即失效） |
| POST | `/api/v1/auth/logout` | 撤销指定 refresh token |
| POST | `/api/v1/auth/logout-all` | 撤销该账号全部 refresh token |
| GET | `/api/v1/auth/me` | 当前用户信息 |

**安全设计要点**：

1. **JWT 对称签名（HS256）**：密钥来自环境变量 `JWT_SECRET` 或本地文件 `data/jwt-secret.local`（自动生成 64 字节随机值），要求 ≥32 字节；校验时检查 issuer 和 audience。
2. **防用户枚举**：用户名不存在时也会执行一次 BCrypt `matches`（用预先计算的 dummy hash），使"用户名不存在/密码错误"的响应时间一致。
3. **暴力破解防护**：连续失败 5 次锁定 15 分钟，且每次失败都记录。
4. **接口放行白名单**：GET 类公开接口（feed、videos、search、users 资料、comments、uploads 静态资源）+ POST 的 auth 与推荐接口公开；`/accounts/**`、`/import/**` 仅 `ROLE_ADMIN`。
5. **身份一致性校验**：请求体里若带 `userId`，`AuthenticatedUser.requireUser` 会强制与 JWT subject 一致，杜绝越权操作。

### 5.2 内容与 Feed 模块

**相关文件**：`controller/ContentController.java`、`controller/FeedController.java`、`service/ContentService.java`、`repository/MyBatisContentRepository.java`、`mapper/content/ContentMapper.xml`

**内容类型**：`video` / `image` / `album`（图集），统一收敛成 `CardItemDto` 的三种子类。

**卡片组装逻辑**（`MyBatisContentRepository.toCard`）：

```
ContentRow (一行内容)
   ├─ 标签列表   findTags(contentId)         （每行 1 次查询）
   ├─ 推荐词     findRecommendWords(contentId)（每行 1 次查询）
   ├─ 首资源     findFirstAsset(kind)
   ├─ 多清晰度   qualityUrls  (video)
   ├─ HLS 地址   hlsUrl       (video)
   └─ 图集分页   albumSlides  (album)
```

- `resolveMediaUrl`：如果资源有 `storage_key` 且配置了 `MEDIA_PUBLIC_BASE_URL`，则拼接成对象存储公网地址，否则原样返回 URL。
- **当前实现方式（注意）**：`feed()`、`videos()`、`search()` 都是**先把全部数据查进内存，再用 `paginate()` 切片**。这是后续改进的首要目标（见 §9）。

### 5.3 社交互动模块

**相关文件**：`service/SocialService.java`、`service/InteractionService.java`、`service/CommentService.java`、`service/ProfileService.java`

| 能力 | 实现要点 |
|---|---|
| 关注/取关 | `follows` 表主键 `(follower_id, following_id)` 天然防重；`insertFollowIfAbsent`；关注时 `lockUser` 行锁防并发；更新 `user_social_stats`；**回填（backfill）**最近内容到关注流 |
| 关注偏好 | 每个关注关系可设 `notify_new_content`、`muted`；静音会删除该作者的 feed 条目，解除静音回填 |
| 点赞/收藏 | 关系表主键防重复；`DuplicateKeyException` 捕获后返回 `changed=false`；收藏落在默认收藏夹 `collections` |
| 评论/回复 | `parent_id` 支撑楼中楼；校验父评论必须属于同一内容 |
| 主页 | 三个 Tab：`posts` / `liked` / `favorites`，按 `show_on_profile` 等字段过滤，本人视角与访客视角不同 |

**一个值得注意的细节**：关注时 `backfillFeed` 会把被关注作者最近 `follow-backfill-size`（默认 100）条已发布内容写入粉丝的 `user_feed_entries`，保证"刚关注就能看到对方近期内容"，同时不影响发布时的全量扇出。

### 5.4 关注流与消息扇出

**相关文件**：`service/FollowingFeedService.java`、`event/messaging/FanoutPlanner.java`、`event/messaging/FanoutChunkProcessor.java`、`persistence/mapper/event/FanoutMapper.xml`

- **表**：`user_feed_entries(user_id, content_id, author_user_id, source_event_id, score)`，主键 `(user_id, content_id)`，`score` 取发布时间，读取时 `score desc, content_id desc` 排序。
- **读路径**：`FollowingFeedService.page` 用 `CursorCodec` 把 `(publishTime, contentId)` 编成不透明游标，`findFeedKeys` 用 `(score < ? or (score = ? and content_id < ?))` 做键集分页（keyset pagination），稳定高效。
- **写路径**：见 §4.3 扇出流程图。
- **大 V 保护**：`fanout-on-write-max-followers`（默认 10 万）——超过阈值的作者不写 feed，读取时 `findFeedKeys` 里会动态 join 该作者的新内容，实现"读时合并"。

### 5.5 通知模块

**相关文件**：`service/NotificationService.java`、`event/messaging/NotificationWriter.java`、`persistence/mapper/event/EventNotificationMapper.java`

- 通知表含 `dedupe_key`，唯一索引 `(receiver_user_id, dedupe_key)`，保证"同一事件同一类型"只生成一条。
- 未读数单独存 `notification_unread_stats`，避免每次 `COUNT(*)`。
- `/me/notifications` 用游标分页；旧版 `/users/{id}/notifications` 是一次性全量返回。
- 每次生成通知会顺带把用户设备入队 `notification_deliveries`，衔接推送模块。

### 5.6 推荐模块

**相关文件**：`recommendation/*`（约 18 个类）

**四个接口**：

| 接口 | 用途 | 场景 |
|---|---|---|
| `POST /api/v1/feed/pages` | 推荐 Feed | 首页 |
| `POST /api/v1/search/guesses` | 搜索联想词 | 搜索页 |
| `POST /api/v1/search/videos` | 内容搜索 | 搜索页 |
| `POST /api/v1/events:batch` | 行为上报 | 曝光/播放/点赞/收藏等 |

**核心概念**：

1. **Actor**：未登录时用 `X-Install-Id` 的 SHA-256 作为身份；登录后优先用 JWT `sub`。推荐表只存哈希，不存明文身份。
2. **会话快照**：每次排序的结果固化成一个不可变的 `Snapshot`，游标翻页只在该快照内做 offset 切片，**翻页不会导致排序漂移**。
3. **签名游标/曝光 token**：用 HMAC-SHA256 签名（复用 JWT 密钥），防篡改、防跨用户、防过期重放。
4. **兴趣模型**：`recommendation_user_interests(actor_key, interest_key, weight)`，行为事件按类型加权（曝光 0.05、播放 0.4、观看按时长、点赞 3、收藏 4、分享 3.5）。
5. **语义向量**（可选）：Ollama 生成 embedding 存 pgvector，`SemanticScoreProvider` 提供搜索/Feed/联想词的相似度分，融入排序；语义服务挂了自动降级到词法排序（`safely()` 包装 + 1 分钟限频告警）。
6. **幂等**：`recommendation_client_requests` 表按 `(actor_key, endpoint, client_request_id)` 保存请求哈希与响应，重放直接返回原响应（24h 内）。
7. **策略护栏**：`policyVersion = "organic-only-v1"`——当前只投递自然内容（视频且 published + public），无广告分支。

### 5.7 聊天模块

**相关文件**：`chat/ChatController.java`、`chat/ChatService.java`、`chat/realtime/*`

**规则**：
- 单聊/群聊都要求**双方互相关注**才能建会话；群成员上限 200。
- 消息有数据库自增 `sequence_no` 作为全局有序号，配合 `client_message_id` 唯一键做客户端幂等。
- 已读回执：成员表存 `last_read_message_sequence`。
- 群生命周期：创建、改名、加人、踢人、转让、解散、退出；解散有状态约束（见 V11 迁移的 CHECK 约束）。

**WebSocket 网关**（`ChatWebSocketGateway`）：
- Netty `ServerBootstrap` 独立运行，`SmartLifecycle` 管理启停。
- 握手时用 `JwtDecoder` 校验 JWT；`ChannelRegistry` 维护 user_id → channel 映射。
- 帧处理器做 Ping/Pong 心跳；空闲超时断开。
- 广播通过领域事件触发：`CHAT_MESSAGE_CREATED` → 查在线成员 → 推送。

### 5.8 推送模块

**相关文件**：`push/*`、`config/PushProperties.java`

- 支持多厂商：`fcm` / `huawei` / `xiaomi` / `oppo` / `vivo`，设备 token 表记录 `push_provider`。
- `RoutingPushSender` 按 provider 路由到对应 `PushAdapter`；当前只内置 FCM 适配器，其余厂商需要扩展。
- 投递状态机：`pending → processing(claimed) → delivered / retry / dead`，带认领超时恢复（防止 worker 崩溃导致消息卡死）。
- 重试退避：5s 起步，指数增长，上限 15m，最多 8 次。
- 全部通过 `@Scheduled` 驱动，不引入额外中间件。

### 5.9 数据导入模块

**相关文件**：`service/MediaCrawlerImportService.java`、`persistence/mapper/content/MediaCrawlerImportMapper.xml`

- 入口：`POST /api/v1/import/media-crawler-jsonl`（仅 ADMIN，Nginx 层也会屏蔽）。
- 逐行解析 JSONL；按 `aweme_id`（视频）/ `id`（图片笔记）去重，已有则更新。
- 自动生成/更新作者（`dy_` 前缀 + 来源 ID）；同步标签、推荐词、互动统计、媒体资源（支持本地路径 → `storage_key`，或直接 URL，识别 HLS/MP4 等格式）。
- 返回 `{read, inserted, updated, skipped, errors}` 统计，最多收集 20 条错误行。

### 5.10 事件驱动与 Outbox

**相关文件**：`event/outbox/*`、`event/messaging/*`、`persistence/mapper/event/OutboxEventMapper.xml`

**为什么用 Outbox 而不是直接发 Kafka？**
保证"业务数据写入"和"事件发布"的**原子性**。直接发消息队列存在"数据库写成功、消息没发出去"的风险；Outbox 把事件和业务数据放在**同一个本地事务**里写库，再由后台任务可靠地投递，是分布式事务的经典替代方案。

**运行模式**（两个开关决定）：

| `local-dispatcher-enabled` | `kafka.enabled` | 行为 |
|---|---|---|
| true（默认） | false（默认） | `OutboxEventDispatcher` 定时扫表，`LocalOutboxDispatchOperation` 锁行处理，本地消费 |
| false | true | `OutboxKafkaPublisher` 把 outbox 事件发到 Kafka，`KafkaEventListeners` 消费，再走同一套 `DomainEventProcessor` |

**幂等三件套**：
- `consumer_processed_events(consumer_name, event_id)`：同一消费者不重复处理同一事件。
- `notifications.dedupe_key`：不生成重复通知。
- 扇出 chunk 的 `chunkId` 带内容指纹：重试安全。

---

## 6. 数据模型

### 6.1 核心表总览

数据库共 **V1~V13 共 13 个通用迁移 + V14 分 H2/PostgreSQL 两版**，主要表如下（按业务域分组）：

| 域 | 表 | 用途 |
|---|---|---|
| 内容 | `content_items` | 内容主体（类型/标题/状态/可见性/发布时间） |
| 内容 | `media_assets` | 媒体资源（URL、清晰度、HLS/格式、宽高时长） |
| 内容 | `content_tags` / `content_recommend_words` | 标签、推荐词 |
| 内容 | `content_stats` | 点赞/评论/收藏/分享/浏览计数 |
| 用户 | `users` | 用户/作者资料（handle、头像、简介、粉丝模式） |
| 用户 | `accounts` | 登录账号（BCrypt 密码、角色、锁定信息） |
| 用户 | `user_social_stats` | 粉丝/关注计数（冗余，避免 COUNT） |
| 社交 | `follows` | 关注关系 + 偏好（通知、静音、扇出分片） |
| 社交 | `content_likes` / `comment_likes` / `collections` / `collection_items` | 点赞、收藏 |
| 社交 | `comments` | 评论（parent_id 支持回复） |
| 通知 | `notifications` | 通知主体（dedupe_key 防重） |
| 通知 | `notification_unread_stats` | 未读数冗余 |
| 通知 | `user_device_tokens` | 设备 token（多厂商） |
| 通知 | `notification_deliveries` | 推送投递队列（状态机） |
| Feed | `user_feed_entries` | 关注流（扇出产物） |
| 事件 | `outbox_events` | Outbox 事件（状态/重试/认领） |
| 事件 | `consumer_processed_events` | 消费幂等记录 |
| 认证 | `auth_refresh_tokens` | Refresh token（哈希存储、轮换链） |
| 推荐 | `recommendation_serve_sessions` | 推荐会话快照 |
| 推荐 | `recommendation_client_requests` | 请求幂等 |
| 推荐 | `recommendation_client_events` | 行为事件去重 |
| 推荐 | `recommendation_user_interests` | 用户兴趣权重 |
| 推荐 | `content_embeddings` / `suggestion_embeddings` / `user_interest_embeddings` | 向量（pgvector） |
| 聊天 | `chat_conversations` / `chat_conversation_members` / `chat_messages` | 会话/成员/消息 |

### 6.2 关键表设计说明

- **`content_items`** 用 `status`（published/draft…）+ `visibility`（public/private）+ `show_on_profile` 控制"谁能看到"。Feed 查询统一过滤 `status='published' and visibility='public'`。
- **`follows`** 带 `fanout_shard`（`hashCode % 128`），为将来分片/分库预留；`notify_new_content`、`muted` 支撑关注偏好。
- **`user_feed_entries`** 是典型空间换时间的扇出表，`score` 列承载时间戳用于 keyset 分页。
- **`outbox_events`** 字段齐全：`status(pending/processed/dead)`、`retry_count`、`next_attempt_at`、`claimed_at/claimed_by`、`partition_key`，既能本地消费也能转发 Kafka。
- **`chat_messages`** 用 `sequence_no` 全局自增作为消息序号，`(conversation_id, sender_user_id, client_message_id)` 唯一键防客户端重复发送。
- **向量表**：PostgreSQL 版用 `vector` 类型（pgvector 扩展），H2 版用 `clob` 存 JSON 数组，通过 `FloatVectorTypeHandler` 抹平差异。

---

## 7. 配置与部署

### 7.1 核心配置项（`application.yml`，均支持环境变量覆盖）

| 配置前缀 | 说明 | 常用变量 |
|---|---|---|
| `server.port` | REST 端口 | 默认 8080 |
| `spring.datasource` | 数据库连接 | `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` |
| `flower-show.auth.jwt` | JWT 密钥/有效期 | `JWT_SECRET`、`JWT_ACCESS_TOKEN_TTL`(15m)、`JWT_REFRESH_TOKEN_TTL`(30d) |
| `flower-show.chat.websocket` | WS 网关 | `CHAT_WEBSOCKET_ENABLED`、`CHAT_WEBSOCKET_PORT`(8090)、`CHAT_WEBSOCKET_PATH` |
| `flower-show.events.*` | Outbox 调度 | `OUTBOX_FIXED_DELAY_MS`、`OUTBOX_BATCH_SIZE`、`OUTBOX_MAX_RETRIES` |
| `flower-show.messaging.*` | 消息后端开关 | `KAFKA_ENABLED`(默认 false)、`LOCAL_EVENT_DISPATCHER_ENABLED`(默认 true) |
| `flower-show.feed.*` | 扇出阈值 | `FANOUT_ON_WRITE_MAX_FOLLOWERS`(10万)、`FANOUT_CHUNK_SIZE`(1000) |
| `flower-show.recommendation.*` | 推荐参数 | `EMBEDDING_ENABLED`、`EMBEDDING_MODEL`、`RECOMMENDATION_SESSION_TTL` |
| `flower-show.push.*` | 推送参数 | `PUSH_ENABLED`、`FIREBASE_PROJECT_ID` |
| `flower-show.media` / `upload` | 媒体地址 | `MEDIA_PUBLIC_BASE_URL`、上传根目录 |

### 7.2 Docker 依赖（`docker-compose.yml`）

| 服务 | 镜像 | 说明 |
|---|---|---|
| `postgres` | `pgvector/pgvector:0.8.5-pg16` | 业务库（自带向量扩展） |
| `kafka` | `apache/kafka:4.3.1` | 事件消息（默认不启用） |
| `minio` + `minio-init` | `minio/minio` | 媒体对象存储（桶 `flower-show-media` 公共读） |
| `nginx` | `nginx:1.27-alpine` | 网关（配置在 `infra/nginx/default.conf`） |
| `ollama` + `ollama-model` | `ollama/ollama` | 语义 embedding（`semantic` profile，可选） |
| `cloudflared` | `cloudflare/cloudflared` | 公网隧道（`tunnel` / `named-tunnel` profile） |

> Spring Boot 本体**不在** compose 里，由本机 `java -jar` 启动（见 `scripts/start-flowershow.ps1`）。

### 7.3 一键启动脚本

`scripts/start-flowershow.ps1` 完成：拉起 Docker 依赖 → 等健康检查 → Nginx 校验重载 → 启动 Spring Boot（自动 build）→ 等待后端健康 → 创建 Cloudflare 隧道（或复用配置的公网网关）→ 把公网地址写进 Android 工程 `gradle.properties` → 可选构建/安装 App。

### 7.4 环境变量清单

见 `.env.example`：
```
FLOWER_SHOW_PUBLIC_GATEWAY_BASE_URL=   # 公网网关，写回 App
CLOUDFLARE_TUNNEL_TOKEN=                # 命名隧道 token（可选）
```
其余变量全部在 `application.yml` 中以 `${VAR:default}` 形式声明。

---

## 8. 测试策略

项目测试覆盖较全（22 个文件、约 4700 行），分三类：

| 类型 | 示例 | 手段 |
|---|---|---|
| 纯单元测试 | `ContentServiceTest`、`SearchServiceTest`、`RecommendationTokenServiceTest`、`OllamaEmbeddingClientTest`、`RoutingPushSenderTest` | Mockito + 内存对象 |
| 服务单测 | `AuthorAccountServiceTest`、`MediaCrawlerImportServiceTest`、`EmbeddingIndexServiceTest`、`SemanticRecommendationServiceTest`、`RecommendationHybridRankingTest` | Mock Mapper / 假数据 |
| 集成测试 | `AuthIntegrationTest`、`DatabaseIntegrationTest`、`ProfileAndSocialIntegrationTest`、`SocialDeliveryIntegrationTest`、`ChatIntegrationTest`、`ChatWebSocketGatewayIntegrationTest`、`PushDeliveryWorkerIntegrationTest`、`RecommendationApiIntegrationTest`、`RecommendationIdempotencyExpiryIntegrationTest`、`ContentViewerInteractionNotificationIntegrationTest`、`PushProviderRoutingIntegrationTest` | `@SpringBootTest` + H2 |

**亮点**：推荐子系统有专门针对幂等、会话过期、混合排序的测试；聊天有真实 WebSocket 网关启停的集成测试；推送有投递 worker 的状态机测试。

---

## 9. 改进建议

按"投入产出比"分为 **P0（生产就绪前应处理）→ P1（架构演进）→ P2（工程化打磨）**。每条给出：问题定位（文件/代码行依据）→ 建议 → 收益。

### P0｜上线前必须处理

#### 9.1 Feed / 搜索 / 评论列表存在"全表加载到内存"问题

- **问题**：`ContentService.feed()`/`videos()` 的 `paginate()`（`ContentService.java` 第 94~103 行）先把 `findAllFeedItems()` 返回的**全部内容**加载进内存再切片；`SearchService.search()` 同样遍历全量。数据量一旦上万，接口延迟和内存都会崩。评论列表 `CommentService.findComments()` 也无分页。
- **建议**：改为 SQL 层分页。Feed/视频用 `LIMIT/OFFSET` 或 keyset 分页（参考 `FollowingFeedService` 已经用得很好的游标方式）；搜索改 `LIKE` 或接入 PostgreSQL 全文检索/向量检索；评论列表加分页或"最多 N 条"。
- **收益**：接口复杂度从 O(N) 降到 O(页大小)，是大数据量下的头号风险点。

#### 9.2 卡片组装存在 N+1 查询

- **问题**：`MyBatisContentRepository.toCards()` 对每一行内容都执行 `findTags` + `findRecommendWords` + `findFirstAsset`（video 还有 qualityUrls/hls），一页 20 条 ≈ 60~100 次 SQL。
- **建议**：按 contentId 批量查询（`WHERE content_id IN (...)`）一次取回，再用 Map 组装；或 MyBatis 关联查询/`collection` 映射一次性返回。
- **收益**：Feed 延迟可降数倍。

#### 9.3 上传接口缺少类型/大小限制与恶意文件防护

- **问题**：`UploadService.store()` 只校验"文件非空"，没有扩展名白名单、大小上限、文件内容嗅探；文件名用随机 UUID 已避免路径穿越，但**任何人都可以传任意大文件**。
- **建议**：配置 `spring.servlet.multipart.max-file-size`；服务端校验 MIME/魔数；对图片/视频分类；生产环境改为**直传对象存储（MinIO 预签名 URL）**，绕开应用服务器磁盘和带宽。
- **收益**：防存储滥用，为媒体托管规模化铺路。

#### 9.4 缺少统一响应格式与全局异常处理器

- **问题**：普通接口直接返回 DTO（`{success:...}` 只是文档设想），推荐接口返回另一套 `ApiEnvelope{requestId,data,error}`，`HealthController` 又返回 `Map`——**三种风格并存**。全局只有推荐域有 `@RestControllerAdvice`，普通接口的错误都是 Spring 默认格式。
- **建议**：统一响应规范（如 `{code,message,data}`），加一个全局 `@RestControllerAdvice` 收敛异常；推荐接口可保留独立 envelope，但要在文档里明确这是"机器协议"而非统一响应。
- **收益**：客户端错误处理代码大幅简化。

#### 9.5 JWT 密钥与密钥管理

- **问题**：`SecurityConfig` 若没配 `JWT_SECRET`，会自动生成密钥文件 `data/jwt-secret.local`——**每个实例密钥不同**，多实例部署时互相不认 token；密钥文件若被提交到仓库则泄露。
- **建议**：生产环境必须显式配置强随机 `JWT_SECRET`（或用 KMS/Vault），并确保 `.gitignore` 排除密钥文件。
- **收益**：多实例可水平扩展，且避免密钥泄露。

### P1｜架构演进

#### 9.6 把"全量 Feed"升级为"推荐 Feed"，两个 Feed 并存造成重复

- **问题**：项目里同时有 `GET /feed`（全量倒序）、`GET /feed/following`（关注流）、`POST /feed/pages`（推荐）三套内容流，职责重叠，客户端容易困惑。
- **建议**：明确演进路径——首页切到推荐接口；保留 `/feed/following` 作为"关注 Tab"；`/feed` 全量接口作为兜底/调试，或删除。
- **收益**：减少维护面，产品形态更清晰。

#### 9.7 缓存缺失

- **问题**：热度 Feed、用户资料、内容详情这类读多写少数据每次都查库；推荐的兴趣表也没有缓存。
- **建议**：引入 Redis：① Feed/卡片 5~30s 缓存；② 通知未读数缓存；③ 推荐排序结果短暂缓存；④ 可用 Redis Stream 替代/补充 outbox 本地轮询。
- **收益**：数据库压力显著下降，接口 P99 改善。

#### 9.8 扇出写放大与"读时合并"逻辑的完善

- **问题**：写时扇出对"粉丝数中等"的作者仍然每次发布写 N 行 `user_feed_entries`；大 V 走读时合并，但合并 SQL 的复杂度与正确性需要持续关注。
- **建议**：对超大户使用"粉丝取关时才删 feed、新内容只写活跃粉丝"或**延迟批量扇出**；可考虑把 `user_feed_entries` 按时间分桶/冷热分离。
- **收益**：避免爆款作者发布时打爆数据库写入。

#### 9.9 Kafka 路径尚未覆盖全链路

- **问题**：Kafka 开关只切换了 outbox 的"转发 + 消费"，但扇出、通知等在 Kafka 模式下依赖 `KafkaEventListeners` 的事件路由，需要更充分的集成测试；`OutboxKafkaPublisher` 与 `LocalOutboxDispatchOperation` 的**并发互斥**（都扫同一张表）也需验证。
- **建议**：为 Kafka 模式补集成测试；明确"单机 = 本地分发，多实例 = Kafka"的切换手册；考虑用事务性消息（Exactly-once）替代"发送后标记"。
- **收益**：为水平扩展铺路时更稳妥。

#### 9.10 聊天实时推送目前只有"新消息"一类事件

- **问题**：群解散、转让、成员变更等事件没有实时广播，客户端只能轮询 REST 才感知。
- **建议**：扩展 `ChatRealtimeEventBroadcaster` 支持群状态事件；WebSocket 网关可加"会话级订阅/消息确认"。
- **收益**：群体验更完整。

### P2｜工程化与可维护性

#### 9.11 大型 Service 拆分

- **问题**：`ChatService`（约 650 行）、`RecommendationService`（约 690 行）、`MediaCrawlerImportService`（约 520 行）都偏大。
- **建议**：按职责拆小类（如 ChatService → ConversationService / MessageService / MembershipService），配合包内私有类。
- **收益**：可读性、可测试性提升。

#### 9.12 清理遗留实现与重复代码

- **问题**：`InMemoryContentRepository` 已无生产用途；`/users/{id}/following-feed` 与 `/feed/following` 逻辑重复；`ContentService` 有两个构造器（一个注入 no-op 事件发布器），测试和生产行为易分叉。
- **建议**：删除内存仓储与死代码；合并重复接口；统一用 Mockito 而非 no-op 构造器。
- **收益**：减少困惑，降低维护成本。

#### 9.13 日志、监控与可观测性

- **问题**：日志以 `log.info/warn` 为主，缺少结构化日志（traceId 只在推荐信封里）、指标与链路追踪。
- **建议**：接入 Micrometer + Prometheus + Grafana（Spring Boot Actuator 已随 starter 可加）；日志加 traceId（可参考推荐信封的 requestId 思路）；outbox/推送失败加告警。
- **收益**：线上问题定位从"看日志"升级为"看指标看链路"。

#### 9.14 安全加固补充

- **建议清单**：① 全局接口限流（尤其是 auth/login、events:batch、feed/pages）；② CORS 从 `allowedOriginPatterns("*")` 收敛为白名单（虽 App 场景影响小）；③ 管理员操作审计日志；④ 导入接口增加任务表/进度/失败明细（需求文档 F10 已有规划）；⑤ 密码策略与找回流程。
- **收益**：满足生产安全基线。

#### 9.15 容器化 Spring Boot 本体

- **问题**：后端不在 compose 中，依赖本机 JDK/脚本启动，环境差异大。
- **建议**：增加 `Dockerfile`（多阶段构建，JRE 17）并纳入 compose；用健康检查/优雅停机；配合 nginx 固定内部网络。
- **收益**：环境可复现，部署标准化。

---

## 10. 图表生成 Prompt 汇总

> 以下 prompt 可直接复制给 ChatGPT（或 DALL·E / 其他绘图模型）生成配图。建议指定"中文标注、浅色科技风、清晰分层"。

### 10.1 整体分层架构图

```
请生成一张"Spring Boot 分层架构"的架构图（中文标注，浅色现代科技风）。
从上到下五层：
1) 客户端层：Android App（Kotlin/Compose/Media3），标注"REST + WebSocket"两个入口；
2) 接入层：Nginx 网关(8088) + Cloudflare Tunnel，标注"反代 REST、WebSocket、/media"；
3) 应用层：Spring Boot(8080)，内部横向分为 Controller → Service → Mapper，标注 MyBatis；
4) 异步层：Outbox 事件表 → 后台任务(Outbox 调度/扇出/通知/推送)，标注"本地分发 或 Kafka"；
5) 存储层：PostgreSQL(pgvector) + MinIO + Kafka + Ollama。
层与层之间用箭头标出请求流向与事件流向。请保证层间有明确分界、色彩区分、模块名清晰。
```

### 10.2 内容发布 → 关注流扇出时序图

```
请生成一张"内容发布与关注流扇出"的 UML 时序图（中文标注）。
参与对象：作者App → ContentController → ContentService → outbox_events → OutboxEventDispatcher → DomainEventProcessor → FanoutPlanner → FanoutChunkProcessor → user_feed_entries/notifications → 粉丝App。
关键步骤：
1) 作者发布内容；
2) 同一事务内写 content_items 并插入 outbox 事件；
3) 返回"发布成功"给作者；
4) 后台定时扫描到事件；
5) 查询粉丝分页列表；
6) 逐批写入粉丝的 user_feed_entries（幂等）；
7) 需要通知的粉丝生成通知；
8) 粉丝请求关注流得到新内容。
请在时序图上用不同颜色区分"同步返回"与"异步处理"两段。
```

### 10.3 认证与令牌时序图

```
请生成一张"登录与刷新令牌"的时序图（中文标注）。
流程：App → AuthController → AuthService → accounts/auth_refresh_tokens 表。
1) 登录（用户名+密码）；
2) 校验锁定状态；
3) BCrypt 校验密码；
4) 签发 Access Token(JWT 15min) + Refresh Token(哈希存储 30天)；
5) 刷新：校验 refresh token 哈希 → 轮换（旧 token 标记 replaced_by）→ 返回新令牌对；
6) 登出/登出全部：撤销一个或全部 refresh token。
请突出"刷新令牌轮换"这一步（比如用高亮色标出旧令牌失效）。
```

### 10.4 推荐系统概念图

```
请生成一张"推荐系统数据流"的示意图（中文标注，横向数据流）。
左侧输入：
- 内容库（视频元数据、互动统计）
- 用户行为（impression/play/like/favorite/share，带 exposureToken 防作弊）
- 兴趣表（interest weight）
- 语义向量（Ollama embedding + pgvector）
中间处理：
- 打分公式（热度 0.48 + 新鲜度 0.25 + 兴趣 0.55 + 语义 0.18）
- 会话快照（不可变排序）
- 签名游标 / 曝光 token
右侧输出：
- Feed 卡片（含 exposureToken、rank、policyVersion）
请用不同颜色的箭头区分"同步计算"和"异步行为回流"，并标注"幂等键 clientRequestId"。
```

### 10.5 数据库 ER 图（核心域）

```
请生成一张"FlowerShow 核心数据库 ER 图"（中文标注，实体+关系）。
核心实体：
- users (用户/作者)
- accounts (登录账号，1对1 users)
- content_items (内容，多对1 users)
- media_assets (媒体，多对1 content_items)
- content_stats (1对1 content_items)
- comments (树形 parent_id，多对1 content_items/users)
- content_likes / collections+collection_items / comment_likes (互动关系)
- follows (关注，双外键到 users，带偏好字段)
- user_feed_entries (关注流扇出，user+content)
- notifications / notification_deliveries / user_device_tokens (通知与推送)
- outbox_events / consumer_processed_events (事件)
- chat_conversations / chat_conversation_members / chat_messages (聊天)
- recommendation_serve_sessions / client_requests / client_events / user_interests (推荐)
请用 Crow's Foot 或类似记号标出基数，主题色区分业务域（内容/用户社交/通知推送/事件/聊天/推荐）。
```

### 10.6 推送投递状态机

```
请生成一张"推送投递状态机"图（中文标注）。
状态：pending → processing(claimed) → delivered / retry → dead。
- pending 被 worker 认领后变 processing；
- processing 成功 → delivered；
- processing 失败可重试 → retry（指数退避 5s~15m）；
- 超过 8 次 → dead；
- processing 超时（认领超时 2m）→ 回到 pending 重新认领（恢复）；
- 设备失效(INVALID_RECIPIENT) → 直接失败并标记设备停用。
请用箭头标注触发条件，用颜色区分成功/失败/恢复路径。
```

### 10.7 部署拓扑图

```
请生成一张"FlowerShow 部署拓扑图"（中文标注，云端+本地混合）。
- 公网：Cloudflare Tunnel → 用户 App；
- 网关层：Nginx(8088)，内部路由到 Spring Boot(8080) 与 Netty WS(8090)，/media 反代 MinIO；
- 后端：Spring Boot(8080) + Netty WebSocket(8090)；
- 数据层：PostgreSQL(pgvector) / Kafka / MinIO / Ollama（虚线表示可选）；
- 标注各端口与主要协议(HTTP/WebSocket/S3)。
请用"虚线"标注可选组件（Kafka、Ollama），用图标区分数据库/消息队列/对象存储。
```

---

## 附：快速阅读指南

| 你想了解… | 去读… |
|---|---|
| 这个服务端到底有哪些功能 | `docs/flower-show-server-functional-requirements.md` §4 |
| 登录/鉴权怎么做的 | 本文 §4.2、§5.1 |
| 关注流为什么这么快 | 本文 §4.3、§5.4 |
| 推荐系统原理 | 本文 §4.5、§5.6 |
| 新增一个业务接口该改哪里 | 本文 §3.1 包结构 → controller → service → mapper |
| 数据库表结构 | `src/main/resources/db/migration/V*.sql` + 本文 §6 |
| 本地怎么跑起来 | `scripts/start-flowershow.ps1`、`docker-compose.yml` |

*本文档基于源码阅读整理，代码路径如有变动请以最新代码为准。*
