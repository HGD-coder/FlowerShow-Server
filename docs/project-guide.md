# FlowerShow 服务端项目导读

> 目标读者：第一次接触本项目、希望在几个小时内建立完整认知的开发者。
> 本文档基于 2026-08 的源码逐一核实写成，所有结论都能在代码中找到出处（文中标注了关键文件路径）。
>
> 与其他文档的关系：`README.md` 偏"怎么跑"，`docs/technical-guide.md` 偏"实现细节手册"，各 `*-api.md` 偏"接口契约"。**本文档偏"怎么理解"**——用一条主线把整个项目串起来，并在第 8 章集中回答"为什么选这些技术、这样做的好处是什么"。

---

## 目录

1. [这个项目是什么](#1-这个项目是什么)
2. [十分钟跑起来](#2-十分钟跑起来)
3. [运行形态：进程、端口与外部依赖](#3-运行形态进程端口与外部依赖)
4. [代码地图：源码该怎么读](#4-代码地图源码该怎么读)
5. [主线故事：一次"点赞"的完整旅程](#5-主线故事一次点赞的完整旅程)
6. [子系统详解](#6-子系统详解)
7. [数据模型速览](#7-数据模型速览)
8. [技术选型详解：为什么这样做，好处在哪](#8-技术选型详解为什么这样做好处在哪)
9. [贯穿全项目的可靠性主题：幂等](#9-贯穿全项目的可靠性主题幂等)
10. [现状短板与演进方向](#10-现状短板与演进方向)
11. [术语表](#11-术语表)
12. [延伸阅读](#12-延伸阅读)

---

## 1. 这个项目是什么

**FlowerShow 是一个"花卉短视频 + 社区"App 的服务端**。你可以把它想象成"主题限定在花艺与植物的小红书/抖音"：用户刷短视频和图集、搜索内容、点赞收藏评论、关注作者、收通知、和作者私信聊天。客户端是一个 Android App（Kotlin + Compose + Media3），本项目就是它背后的整个后端。

从用户视角，服务端承载的功能有八块：

| 功能 | 用户看到什么 | 对应接口（节选） |
|---|---|---|
| 内容浏览 | 首页 Feed、视频列表、搜索、推荐词 | `GET /api/v1/feed`、`GET /api/v1/videos`、`GET /api/v1/search` |
| 个性化推荐 | 首页推荐流、搜索联想、行为反馈 | `POST /api/v1/feed/pages`、`POST /api/v1/search/guesses`、`POST /api/v1/events:batch` |
| 账号 | 注册、登录、令牌刷新、登出 | `POST /api/v1/auth/*` |
| 社交 | 关注/粉丝、点赞、收藏、评论、个人主页 | `/api/v1/users/*`、`/api/v1/contents/{id}/like|favorite` |
| 关注流 | "关注"Tab 里看到关注者的新内容 | `GET /api/v1/feed/following` |
| 通知 | 点赞/评论/关注/更新提醒、未读数、离线推送 | `/api/v1/me/notifications/*` |
| 聊天 | 单聊、群聊、实时消息、已读 | `/api/v1/chat/*` + WebSocket |
| 媒体 | 视频多清晰度 MP4、自适应 HLS、图片 | `media_assets` 表 + MinIO/Nginx |

此外还有两个"幕后台前都不面向普通用户"的能力：

- **数据导入**：把 MediaCrawler 爬取的抖音 JSONL 元数据批量导入为平台内容（`POST /api/v1/import/media-crawler-jsonl`，仅管理员可用，网关层还会再拦一次）。
- **运维任务**：每日凌晨 3:30 清理过期的 outbox 事件、幂等记录、终态投递、推荐会话等只增不减的簿记表（`maintenance/MaintenancePurgeJob.java`）。

一句话概括架构（后面各章会展开）：

> 这是一个**经典的 Spring Boot 分层单体**（Controller → Service → MyBatis Mapper → PostgreSQL），但它内部长出了一套**"事务性 Outbox 事件总线"**，把通知、关注流扇出、实时聊天广播、推送这些"写完库之后的后续动作"全部异步化；推荐子系统自成一体，带会话快照、签名游标和防作弊曝光令牌；聊天的实时通道由**同进程内独立的 Netty WebSocket 监听器**（8090 端口）承载。

---

## 2. 十分钟跑起来

前置要求：JDK 17+、Maven 3.9+、Docker（桌面版即可）。

```powershell
# 1. 拉起基础设施：PostgreSQL(pgvector) + Kafka + MinIO + Nginx
docker compose up -d kafka postgres minio minio-init nginx

# 2.（推荐）一键启动：起依赖 → 构建/启动 Spring(8080) → 起 Cloudflare 隧道 → 把公网地址写进 App 工程
powershell -ExecutionPolicy Bypass -File .\scripts\start-flowershow.ps1

# 或者手动启动（无 Kafka 模式，本地事件分发器兜底，功能闭环不受影响）
mvn spring-boot:run
```

启动后：

- REST 服务：`http://localhost:8080`
- 统一网关（App 实际访问的入口）：`http://localhost:8088`，健康检查 `http://localhost:8088/health`
- 聊天 WebSocket（内部诊断用）：`ws://localhost:8090/ws/chat`；App 走的是 `wss://{公网域名}/ws/chat`，由 Nginx 转发
- 数据库：`jdbc:postgresql://localhost:5432/flower_show`，用户名/密码都是 `flower_show`

几个对开发体验很重要的默认行为：

- **JWT 密钥自动生成**：没设 `JWT_SECRET` 时，服务会在 `data/jwt-secret.local` 生成一个 64 字节随机密钥并复用（已 gitignore）。生产必须显式配置 `JWT_SECRET`（≥32 字节），否则多实例互不认 token。
- **Kafka 默认关闭**：单机开发不需要 Kafka，事件由本地分发器消费，功能闭环完整。要开集群模式：`KAFKA_ENABLED=true` + `LOCAL_EVENT_DISPATCHER_ENABLED=false`。
- **语义搜索默认关闭**：`EMBEDDING_ENABLED=false`。开启需要 `docker compose --profile semantic up -d ollama` 并拉取 `qwen3-embedding:0.6b` 模型；Ollama 挂了也不影响服务，自动降级为关键词/规则排序。
- **测试不用装数据库**：测试跑在 H2 的 PostgreSQL 兼容模式上（`src/test/resources/application.properties`）。

---

## 3. 运行形态：进程、端口与外部依赖

生产/联调的完整拓扑（`docker-compose.yml` + `scripts/start-flowershow.ps1`）：

```text
                    Android App
                    /          \
        REST + wss(公网)         媒体下载
                    \          /
              Cloudflare Tunnel（cloudflared，公网入口，免公网 IP）
                          |
                    Nginx 网关 :8088  ── 唯一对外入口
        ┌─────────────────┼──────────────────────┐
        │  /api/*          │  /ws/chat            │  /media/*
        ▼                  ▼                      ▼
  Spring Boot :8080   Netty WS :8090          MinIO :9000
  （REST + 全部业务）  （同一 JVM 进程内的        （对象存储，
        │              独立 Netty 监听器）        桶 flower-show-media）
        ▼
  PostgreSQL :5432（pgvector/pgvector:0.8.5-pg16 镜像）
        ▼
  outbox_events 表 ──(100ms 轮询)──► 事件分发
        │                                    │
        ├── 本地分发器（默认）                  ├── Kafka :9092（可选开关）
        ▼                                    ▼
  通知 / 关注流扇出 / 聊天广播 / 推送投递（同一套消费逻辑）
  
  Ollama :11434（可选，semantic profile，跑 embedding 模型）
```

要点：

1. **Spring Boot 本体不在 Docker 里**，由启动脚本用本机 JDK 拉起（`target/runtime` 下有 PID 和日志），依赖容器化、应用本体本机跑——这是刻意的开发期形态，方便改代码即重启。
2. **Nginx 是唯一对外入口**，做了四件事：反代 REST（把 `Authorization`、`X-Install-Id` 头透传给后端）、反代聊天 WebSocket（`/ws/chat` → 8090，读超时放到 1 小时）、反代 MinIO 媒体（透传 `Range`/`If-Range` 支持断点，加一年期不可变缓存头）、**直接 403 掉 `/api/v1/import/`**（导入接口即使后端疏忽也不能从公网调用，属于纵深防御）。
3. **三个 Java 监听端口各司其职**：8080 REST（Tomcat）、8090 WebSocket（Netty）、5432 上的 PostgreSQL 归 Docker。REST 和 WS 在同一个 JVM 里，共享 Spring 容器、`JwtDecoder` 和数据库连接池，但线程模型完全独立——WS 的长连接不会占满 REST 的线程池。

---

## 4. 代码地图：源码该怎么读

源码根包 `com.github.hgdcoder.flowershow`，共约 180 个 Java 文件。按"读的优先级"排：

```text
flowershow/
├── FlowerShowServerApplication   # 启动类：@MapperScan + @EnableScheduling，并把 JVM 时区固定为 UTC
│
├── controller/   (9 个)          # REST 入口，薄。参数校验 + 鉴权 + 调 Service
├── service/      (14 个)         # 业务规则 + 事务边界。大部分"为什么"都在这里
│
├── event/                        # ★ 全项目最有含金量的包：事件驱动核心
│   ├── DomainEvent/EventTypes    #   领域事件抽象，共 10 种事件类型
│   ├── outbox/                   #   事务性 Outbox：写入、调度、锁行、重试、Kafka 发布
│   └── messaging/                #   消费侧：事件分发、关注流扇出、通知生成、幂等存储
│
├── recommendation/               # ★ 推荐子系统，自成一体（含 embedding/ 子包）
│   └── 会话快照、打分排序、打散、HMAC 签名 token、行为事件、Ollama 嵌入
│
├── chat/                         # 聊天：REST 会话/消息 + realtime/ 下的 Netty 网关五件套
├── push/                         # 推送：投递 Worker、状态机、厂商路由、FCM 适配器
│
├── persistence/mapper/           # MyBatis Mapper 接口，按域分包：content/social/event/chat/recommendation
│   └── (对应 resources/mapper/*.xml 共 21 个，SQL 全部手写在这里)
│
├── config/                       # Security/JWT、CORS、Kafka、MyBatis、推送、嵌入、聊天 WS、管理员引导
├── repository/                   # 内容仓储接口 + MyBatis 实现 + 遗留内存实现
├── model/                        # 约 40 个 record：请求体/响应 DTO
├── maintenance/                  # 每日数据保留清理任务
└── security/                     # AuthenticatedUser：JWT 身份与请求体一致性校验
```

**"新增一个业务接口要动哪里"**：`model/` 加请求响应 record → `persistence/mapper/` 加 Mapper 接口 + `resources/mapper/` 加 XML → `service/` 写业务（需要异步后续动作就在事务里调 `eventPublisher.publish(...)`）→ `controller/` 加端点 → `db/migration/` 加 Flyway 迁移。

**"想知道某张表长什么样"**：直接看 `src/main/resources/db/migration/V1~V14`。迁移文件名即历史：V1 内容初始化、V3 账号、V6 认证、V7 社交事件分发（outbox/扇出/投递）、V9 推荐、V10/V11 聊天、V12/V13 推送、V14 语义向量（分 PostgreSQL/H2 两个 vendor 版本）。

---

## 5. 主线故事：一次"点赞"的完整旅程

理解了这个流程，就理解了全项目一半的设计。用户 Alice 点赞了 Bob 的视频：

```text
【同步路径——用户等着的部分，越快越好】
POST /api/v1/contents/{id}/like  (Authorization: Bearer <Alice的JWT>)
  ① SecurityConfig 过滤器链
     NimbusJwtDecoder 验 HS256 签名 + issuer + audience + 过期时间
     → SecurityContext 里挂上 Alice 的身份和角色
  ② ContentController → AuthenticatedUser.requireUser()
     强制"请求体 userId == JWT subject"，防止 Alice 冒充别人点赞
  ③ InteractionService.likeContent()  [@Transactional]
     a. 校验内容和用户存在
     b. insert content_likes（主键冲突 → 说明点过赞，changed=false，幂等）
     c. content_stats 点赞数 +1
     d. eventPublisher.publish(CONTENT_LIKED 事件)   ← 关键！
        这不是发消息，而是往 outbox_events 表 INSERT 一行 status='pending'
        ——它和 a/b/c 在【同一个数据库事务】里
  ④ 返回 200 { changed: true, stats: { likeCount: 43 } }
     Alice 的界面瞬间响应完毕。到这里，Bob 还什么都不知道。

【异步路径——100ms 后开始的后续动作】
  ⑤ OutboxEventDispatcher（@Scheduled，每 100ms 一轮）
     SELECT ... WHERE status='pending' AND next_attempt_at<=now
       ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED   ← 多实例安全
  ⑥ DomainEventProcessor 分发：CONTENT_LIKED →
     ProcessedEventStore 幂等检查（consumer_name+event_id 主键，防重复消费）
     → NotificationWriter：
        - 给 Bob 插一条通知（dedupe_key = eventId + ":like"，唯一索引兜底防重）
        - Bob 的未读数 +1（notification_unread_stats 单独一行，免 COUNT）
        - 给 Bob 每台启用中的设备插一行 notification_deliveries(pending)
  ⑦ PushDeliveryWorker（每 5s，同样 FOR UPDATE SKIP LOCKED 认领）
     按 device 表的 provider 字段路由 → FcmPushSender 调 Firebase
     → 成功 delivered / 可重试失败按 5s~15m 指数退避最多 8 次 / 彻底失败 dead
  ⑧ Bob 的手机弹出推送。若 Bob 恰好在线开着聊天页，通知也在 App 内可见。
```

**这条主线揭示了全项目最重要的架构决策**：凡是"写完数据库之后的后续动作"（通知、扇出、广播、推送），一律不在用户请求的同步路径里做，而是先以事件形式与业务数据**原子地**落进同一张 `outbox_events` 表，再由后台任务异步消化。用户请求只等三件事：业务写库、计数更新、插一行事件——又快又不丢。

如果第 ⑥ 步失败（比如数据库抖动），事件不会丢：重试计数 +1，按 2/4/8/…/256 秒（封顶 300 秒）指数退避，10 次耗尽后标记 `dead` 留待人工处理。已处理的事件由每日清理任务保留 30 天后删除。

---

## 6. 子系统详解

### 6.1 认证与账号（`service/AuthService.java`、`config/SecurityConfig.java`）

**令牌模型**：Access Token 是 15 分钟的 JWT（HS256 对称签名，claims 含 userId、accountId、username、roles；校验签名之外还校验 issuer 和 audience）。Refresh Token 是 48 字节 CSPRNG 随机数，**库里只存 SHA-256 哈希**，有效期 30 天。

**刷新令牌轮换**（`auth_refresh_tokens` 表）：每次 `POST /auth/refresh` 都"旧换新"——旧 token 置 `revoked_at` 并记 `replaced_by_token_id` 指向新 token，形成链。如果有人拿一个**已被用过**的旧 token 来刷新（CAS 更新影响 0 行），系统判定令牌族泄露，**撤销该用户整个令牌族**并返回 401。这是 OAuth 2.0 最佳实践里的 refresh token rotation + reuse detection。

**三个防攻击细节**，都值得学：

1. **防用户枚举**：用户名不存在时，也用一个预生成的 dummy BCrypt 哈希跑一遍 `matches()`，让"用户不存在"和"密码错误"的耗时一致；且先验密码再查锁定状态，避免用 403/429 响应差异枚举出有效用户名。
2. **暴力破解锁定**：连续失败 5 次锁 15 分钟，计数在 SQL 里原子自增，锁不会被无限续期。
3. **身份一致性**：`AuthenticatedUser.requireUser()` 把请求体里的 userId 和 JWT subject 强制对齐，杜绝"带着自己的 token 改别人数据"这类越权。

**管理员引导**（`config/AdminBootstrap.java`）：启动时若设置了 `ADMIN_BOOTSTRAP_USERNAME/PASSWORD`（密码 ≥12 位）且该账号不存在，则创建第一个 admin。没有这个机制，`/api/v1/accounts/**`、`/api/v1/import/**` 这些 `hasRole("ADMIN")` 的接口永远无人可用。

### 6.2 内容与媒体（`service/ContentService.java`、`repository/MyBatisContentRepository.java`）

内容分三类：`video` / `image` / `album`（图集），统一装配成 `CardItemDto` 的三种子类返回。每条内容带标签、推荐词、互动统计；视频还带多清晰度 MP4（`qualityUrls`）与自适应 HLS（`hlsUrl`）。

**媒体地址的两级设计**是这个模块最聪明的地方：`media_assets` 表存**相对的** `storage_key`（如 `videos/{id}/hls/master.m3u8`），API 响应在**请求时**才用 `MEDIA_PUBLIC_BASE_URL` 拼出完整 URL。好处：从"本机 Nginx → Cloudflare Tunnel → 正式 CDN"迁移时，**只改一个环境变量重启即可，数据库一行都不用动**。HLS 转码由 `scripts/convert-videos-to-hls.ps1` 离线完成（NVENC 硬编、2 秒分片、传 MinIO、注册新行），旧 MP4 行保留作回退，接口契约向后兼容。

### 6.3 社交互动与关注流（`service/SocialService.java`、`event/messaging/FanoutPlanner.java`）

关注/点赞/收藏/评论都是"关系表 + 主键天然防重 + 冗余计数表"的经典做法：`content_likes` 主键冲突即"已赞过"（返回 `changed=false`）；`user_social_stats`、`notification_unread_stats` 把粉丝数、未读数从 `COUNT(*)` 变成 `O(1)` 读。

**关注流是本项目最精彩的部分**，采用"写时扇出为主、读时合并兜底"的混合模型：

```text
作者发布内容（同步事务内写 content_* 表 + outbox 事件）
   ↓ 异步
FanoutPlanner 决策：
   - 作者粉丝数 ≤ 100,000（可配）→ 写时扇出（fanout-on-write）
     分页遍历粉丝（每 chunk 1000 人），逐批写 user_feed_entries
     （每个粉丝一行，score = 发布时间，主键 (user_id, content_id) 幂等）
   - 作者粉丝数 > 100,000（大 V）→ 不写扇出表（fanout-on-read）
     读取时由 FollowingFeedMapper 动态 JOIN 大 V 的新内容合并进结果
   ↓
读者 GET /feed/following?cursor=...
   user_feed_entries 按 (score DESC, content_id DESC) 键集分页（keyset pagination）
   游标由 CursorCodec 编码成不透明字符串，翻页稳定不重不漏
```

配套细节：每个关注关系可设偏好——`notify_new_content`（要不要新帖通知）与 `muted`（静音，静音作者的内容既不进 feed 也不产通知）；刚关注某人时会把对方最近 100 条（可配）内容**回填**进你的关注流，保证"关注后立刻有内容可看"。

### 6.4 通知与推送（`event/messaging/NotificationWriter.java`、`push/*`）

事件 → 通知的映射：评论/评论被赞/内容被赞/被收藏/被关注，分别通知对应作者；`CONTENT_PUBLISHED` 走扇出；`CHAT_MESSAGE_CREATED` 走实时广播。

**推送是一个教科书级的数据库任务队列**。`notification_deliveries` 表即队列，状态机为 `pending → processing → delivered / retry / dead`：

- Worker（每 5 秒一轮）用 `FOR UPDATE SKIP LOCKED` 认领到期行，认领即置 `processing` 并记 `claimed_by`（随机实例 ID + 批次 UUID）；
- **网络调用在事务提交之后**才发起，避免拿着数据库连接等 HTTP；
- 认领超时（2 分钟）的行会被其他 Worker 抢救回来，防止 Worker 崩溃导致消息卡死；旧 Worker 不能覆盖新租约；
- 可重试失败按 5s 起步指数退避（封顶 15 分钟），8 次后 `dead`；超过 24 小时的通知不再投递（人早就过气了）；
- FCM 返回 `UNREGISTERED`/`SENDER_ID_MISMATCH` → 该设备被禁用，同设备排队中的投递一并终结；
- **多厂商中立**：设备表带 `push_provider` 字段（fcm/huawei/xiaomi/oppo/vivo），`RoutingPushSender` 按设备路由到对应适配器。目前只有 FCM 有真实网络适配器，其余厂商注册可存但投递落 `dead(PROVIDER_NOT_CONFIGURED)`——**不伪造任何未实现的厂商调用**，为国内厂商通道（见 `docs/mainland-push-strategy.md`）预留了接线位。

### 6.5 推荐子系统（`recommendation/`，约 18 个类，独立协议）

推荐是四个 POST 接口自成一套"机器协议"（统一信封 `{requestId, traceId, serverTimeMs, data, error}`，与普通 REST 风格不同），核心概念四个：

**① Actor（行为主体）**：登录用户 = `sha256("user:" + JWT sub)`；未登录 = `sha256("install:" + X-Install-Id 头)`。推荐表**只存哈希不存明文**，天然规避敏感标识落库。

**② 会话快照（serve session）**：第一次请求（或 `refresh=true`）时完成一次全量打分排序，把**有序结果整体**固化进 `recommendation_serve_sessions.snapshot_json`，TTL 30 分钟。之后翻页只是"在不可变快照上做 offset 切片"——**翻页时排序绝不漂移**，这是它区别于"每次现算"的关键性质。

**③ 打分公式**（`organic-hybrid-v3`）：

```text
score = 0.48 × 热度分        （log1p 加权的播放/赞/藏/分享，批内归一化）
      + 0.25 × 新鲜度分      （exp 衰减，180 天尺度）
      + 0.55 × 兴趣饱和分    （用户兴趣权重求和后 1-e^(-w/5) 压到 0~1）
      + 0.18 × 语义相似分    （可选，pgvector 余弦相似度，阈值 0.35 以下记 0）
排序后经 SeededSessionDiversifier 打散：
  作者重复惩罚 0.14/次，只在"前 12 名候选、分数差距 ≤0.18"的窗口内
  用确定性 Gumbel 加权随机挑选 —— 同一会话内结果确定，不同会话有变化
```

**④ 签名 token 双件套**（`RecommendationTokenService`）：翻页游标和曝光令牌都是"自造 JWT"——`base64url(payload).base64url(HMAC-SHA256)`，**HMAC 密钥复用 JWT 登录密钥，但用域分隔字符串区分用途**（`flowershow/recommendation/cursor/v1` 与 `.../exposure/v1`），两种 token 不可互换，校验用常量时间比较。

**防作弊闭环**是这个子系统最值得称道的设计：服务端下发每条内容时附带绑定（actor、会话、内容、排名、过期时间）的 `exposureToken`；客户端上报行为（`events:batch`，8 种事件）时必须带回该 token，服务端**验签 + 比对 actor + 比对内容 + 回查会话快照确认该内容确实出现在该排名**，全部通过才采信。伪造"我看过/我赞了某条推荐内容"因此极其困难，行为数据（进而兴趣模型）的可信度有了保障。

行为 → 兴趣权重的增量：`search_submit/suggestion_click +20`，`like +3`、`favorite +4`、`share +3.5`、`play_start +0.4`、`watch` 按时长 clamp 到 0.1~3.0、`impression +0.05`，upsert 累加进 `recommendation_user_interests`。

**语义嵌入（可选）**：`EmbeddingIndexWorker` 每 10 分钟对内容文本（标题+描述+标签）做**内容哈希比对增量索引**，调本地 Ollama 的 `qwen3-embedding:0.6b`（1024 维、L2 归一化）写入 pgvector 三张表（内容/联想词/用户画像）。任何环节失败——未启用、H2 环境、Ollama 挂掉——都统一降级为空相似度、纯词法排序，告警日志 60 秒限频。**推荐功能对 AI 基础设施零强依赖**。

### 6.6 实时聊天（`chat/`）

**REST 管数据、WebSocket 管实时**，职责切分非常干净：

- 发消息永远是 REST `POST /chat/conversations/{id}/messages`。服务端校验成员身份与会话状态，消息落库（`sequence_no` 由数据库 identity 列全局自增，天然全序），`client_message_id` 唯一键做**客户端幂等**（网络重试不会发出两条）；
- WebSocket **只推不收业务写**——客户端唯一能上行的是 `{"type":"ack","eventId":...}` 形状的确认帧（仅校验形状，不落库）。在线推送只是"提示"，PostgreSQL 才是唯一事实源，客户端按 `message.id` 去重、断线后用 REST 历史接口补齐空洞。

**Netty 网关**（`chat/realtime/` 五个类）作为 `SmartLifecycle` Bean 与 Spring 同生命周期：`ChatWebSocketHandshakeHandler` 在 Upgrade 握手时复用 REST 同一个 `JwtDecoder` 验 token，失败 401；连接成功回 `{"type":"connection.ready"}`，并调度一个"token 到期时刻关闭连接（close code 4001）"的任务，让 App 走正常刷新路径重连。`ChannelRegistry` 维护 userId → Set\<Channel\>，**一个用户多设备同时在线都收到同一条消息**；心跳为服务端 Ping/客户端 Pong，60 秒空闲断开，帧上限 8KB；慢消费者（channel 不可写）的实时帧直接跳过，靠 REST 兜底，避免一个慢设备拖垮广播。

**群聊生命周期**：创建/改名/加人/踢人/转让/解散/退出，全部要求 owner 权限（退出除外），群上限 200 活跃成员，解散用 CAS 条件更新（幂等），状态约束由 V11 迁移的 CHECK 兜底。**单聊和建群都要求双方互相关注**——社交关系先于聊天关系，和产品形态自洽。已读用每成员一行 `last_read_message_sequence` 游标，只前进不后退，未读数 = sequence 大于游标且非自己发的消息数。

### 6.7 数据导入与运维（`service/MediaCrawlerImportService.java`、`maintenance/`）

导入逐行解析 JSONL，按内容 ID 幂等（已存在则更新），自动生成/维护 `dy_` 前缀的作者账号，同步标签、推荐词、统计与媒体资源（识别 MP4/HLS/图片，落 `storage_key`），返回 `{read, inserted, updated, skipped, errors}` 统计。该接口三重防护：Spring Security `hasRole("ADMIN")` + Nginx 公网 403 + 仅本地调用约定。

`MaintenancePurgeJob` 每天 03:30 把七类只增不减的簿记数据按保留期（默认 30 天）清掉：已处理/死亡的 outbox 事件、消费幂等记录、终态推送投递、过期推荐会话/幂等请求/行为事件。没有这个任务，幂等表和 outbox 表会无限膨胀——**幂等是有存储成本的，这个项目把成本管理也做了**。

---

## 7. 数据模型速览

约 40 张表，按域分组记忆：

| 域 | 表 | 一句话职责 |
|---|---|---|
| 内容 | `content_items` / `media_assets` / `content_tags` / `content_recommend_words` / `content_stats` | 内容主体 + 多清晰度/HLS 媒体行 + 标签推荐词 + 冗余计数 |
| 用户 | `users` / `accounts` / `user_social_stats` | 资料与登录账号 1:1 分离（作者可无账号，账号必有用户） |
| 认证 | `auth_refresh_tokens` | 只存 SHA-256 哈希，replaced_by 链支撑轮换与族撤销 |
| 社交 | `follows`（含偏好与 fanout_shard）/ `content_likes` / `comment_likes` / `collections`+`collection_items` / `comments` | 关系全部主键防重；评论 parent_id 支持楼中楼 |
| 关注流 | `user_feed_entries` | 扇出产物，(user_id, content_id) 主键，score 承载时间做键集分页 |
| 通知 | `notifications`（dedupe_key 唯一）/ `notification_unread_stats` / `user_device_tokens` / `notification_deliveries` | 通知事实 + 未读计数 + 设备注册 + 推送队列 |
| 事件 | `outbox_events`（status/retry/next_attempt_at）/ `consumer_processed_events` | 事务性事件存储 + 消费幂等 |
| 聊天 | `chat_conversations` / `chat_conversation_members`（含已读游标）/ `chat_messages`（identity sequence_no） | 会话/成员状态机/全局有序消息 |
| 推荐 | `recommendation_serve_sessions` / `_client_requests` / `_client_events` / `_user_interests` | 快照 / 请求幂等 / 事件去重 / 兴趣画像 |
| 向量 | `content_embeddings` / `suggestion_embeddings` / `user_interest_embeddings` | pgvector，(id, model_key) 唯一，H2 用 CLOB+TypeHandler 抹平 |

两条横切设计：**计数永远冗余存储**（stats 表），**幂等永远靠数据库约束**（主键/唯一索引/CAS），不靠应用层内存状态。

---

## 8. 技术选型详解：为什么这样做，好处在哪

这一章逐项回答"为什么不那样做"。每项格式：**选了什么 → 解决什么问题 → 优点 → 诚实的代价**。

### 8.1 模块化单体 + 事件驱动，而不是一上来就微服务

**做法**：一个 Spring Boot 部署单元承载全部业务，模块间用包边界隔离（`chat/`、`push/`、`recommendation/`、`event/`…），跨模块的后续动作一律走领域事件。

**优点**：开发、调试、部署都是最简形态——一个进程、一个数据库、一条启动命令；本地事务覆盖业务一致性，不存在分布式事务问题；单人/小团队也能维护全部功能。同时事件机制是"微服务的种子"：将来若要拆出通知服务或推荐服务，只需把 outbox 的消费端从"本地分发"切到"Kafka + 独立消费组"，**业务代码一行不改**。

**代价**：所有模块共享一个进程的资源上限；某模块的内存泄漏会拖垮全局。

### 8.2 PostgreSQL 一库两用（关系 + pgvector），而不是引入专用向量库

**做法**：业务数据和推荐向量都放 PostgreSQL，用 `pgvector/pgvector:0.8.5-pg16` 官方镜像，向量检索用内积/余弦算子。

**优点**：**少一个组件就少一整类运维成本**（部署、监控、备份、版本升级、网络拓扑）。对当前规模（候选集上限 200、增量索引、10 分钟刷新），pgvector 的性能完全够用。更关键的是**事务一致性**：向量和业务数据同库，不存在"内容删了、向量库里还有"的跨存储一致性问题。将来真到需要专用向量库的量级，嵌入抽象（`SemanticScoreProvider`）隔离了换实现的改动面。

**代价**：超大规模（千万级向量、高 QPS 近邻检索）下性能不如专用引擎；本项目的回答是——到时候再换，接口已经留好。

### 8.3 MyBatis 手写 SQL，而不是 JPA/Hibernate

**做法**：21 个 Mapper XML 手写全部 SQL，包括大量"非玩具"语句。

**优点**：本项目正确性的命脉恰恰藏在**必须精确控制的 SQL**里——`FOR UPDATE SKIP LOCKED` 认领（outbox、推送队列）、`ON CONFLICT DO NOTHING` 幂等插入、键集分页的 `(score, id) > (?, ?)` 比较、CAS 条件更新（`WHERE status='active'` 且检查影响行数）、按 vendor 分叉的向量语法。这些用 JPA 的派生查询/JPQL 要么表达不出来，要么要绕到原生 SQL，ORM 的抽象红利在这里是负资产。手写 SQL 还让执行计划和索引设计"所见即所得"，排查性能问题不用先翻译一层。

**代价**：样板代码多、字段改名要动 XML；团队需要愿意读 SQL 的人。对以查询为中心的本项目，这个交换是划算的。

### 8.4 Flyway 管理数据库版本，测试用 H2 的 PostgreSQL 兼容模式

**做法**：V1~V14 迁移脚本进仓库，启动时自动执行；`db/vendor/{vendor}` 目录让 V14 这种方言相关迁移按数据库类型分叉（PostgreSQL 用 `vector` 类型，H2 用 CLOB 存 JSON 数组，`FloatVectorTypeHandler` 在 Java 侧抹平差异）。

**优点**：数据库结构与代码同版本演进，任何环境的库都能一键重建到正确状态——**这是"新人第一天就能跑通全部集成测试"的前提**（测试不依赖 Docker 里的 PostgreSQL，22 个测试文件在 H2 上跑完认证、社交、聊天 WebSocket、推送状态机、推荐幂等全链路）。版本化的迁移历史本身就是最好的 schema 变更日志。

**代价**：H2 与 PostgreSQL 并非 100% 兼容（如 `SKIP LOCKED`、`ON CONFLICT` 语义有差异，代码里为此写了分叉语句），偶尔会有"H2 绿了、PG 红了"的漏网情况，需要少量双实现（本项目的 V14 和部分 Mapper 就是这么处理的）。

### 8.5 事务性 Outbox（Transactional Outbox），而不是"写完库再发 Kafka"

**做法**：`OutboxEventPublisher.publish()` 是一个普通 `@Transactional` 方法，在**调用方业务事务内**直接 INSERT `outbox_events`；后台每 100ms 轮询分发。

**优点**：消灭了经典的"双写不一致"。直接发消息队列无法避免两种坏情况——库写成功但消息没发出去（用户动作丢了后续），或消息发出去了但库回滚（凭空产生通知）。Outbox 把"发消息"变成"写本地表"，与业务数据同一个事务原子提交，**要么都在，要么都不在**。后台投递天然 at-least-once，配合消费幂等（见第 9 章）效果等价 exactly-once。附带的好处：事件本身就是审计日志，出问题可以直接查表回放。

**代价**：引入 100ms 量级的分发延迟（对通知/扇出/推送完全无感）；需要维护清理任务控制表膨胀（项目已做）。

### 8.6 用 `FOR UPDATE SKIP LOCKED` 把 PostgreSQL 当任务队列，而不是引入 Redis/RabbitMQ

**做法**：outbox 分发、推送投递、扇出消费，全部用"短事务锁行认领 → 提交后干活 → 条件更新结果"的模式。

**优点**：多 Worker/多实例并发拉取时，`SKIP LOCKED` 让大家天然瓜分不同行、互不阻塞，**不需要任何额外中间件就获得了工作队列的并发安全**。认领超时回收机制防 Worker 崩溃卡死任务。对单机起步的项目，"少一个要运维的组件"是实打实的收益；等真需要横向扩，这些表的模式可以直接换成 Kafka（见 8.7）。

**代价**：轮询间隔（100ms/5s）带来轻微延迟；队列深度受限于单库写入吞吐——但那是日均亿级事件的烦恼，远在当前规模之外。

### 8.7 消息后端可切换：本地分发 ⇄ Kafka，而不是绑定一种

**做法**：`LOCAL_EVENT_DISPATCHER_ENABLED`（默认 true）与 `KAFKA_ENABLED`（默认 false）两个开关，用 `@ConditionalOnExpression` 保证**同一时刻只有一条路径在拉 outbox 表**（避免双发）；两条路径汇入同一个 `DomainEventProcessor`，共用同一套幂等存储。Kafka 生产端 `acks=all` + 幂等 producer，消费端手动提交、`read_committed`。

**优点**：开发机零依赖跑通全功能闭环（不开 Kafka 也有通知、扇出、推送），集成环境一键切到真正的消息总线支撑多实例。**"开发体验"和"生产拓扑"不是二选一**，这是很多项目做不到的。切换/双跑期间共用的幂等层还能兜底重复消费。

**代价**：两套分发实现都要维护和测试；Compose 里的单节点 KRaft Kafka 只够开发，生产要三节点起步。

### 8.8 关注流混合扇出（写时扇出 + 读时合并），而不是纯推或纯拉

**做法**：粉丝 ≤10 万的作者发布时把内容写进每个粉丝的 `user_feed_entries`（写时扇出）；超阈值的大 V 不写，读取时动态合并其新内容（读时合并）。

**优点**：纯"写时扇出"会被百万粉大 V 的一条动态打成百万行写放大；纯"读时合并"则每次刷新都要实时聚合一堆关注源，读延迟不可控。混合模型各取所长：**普通作者读路径 O(页大小)、大 V 写路径 O(1)**，阈值一个配置项可调。读取用键集分页（keyset），比 OFFSET 稳定且不受中途插入影响。

**代价**：读路径存在两种来源的合并逻辑，正确性（不重不漏、排序一致）要靠仔细的 SQL 与测试维持。

### 8.9 JWT（HS256）+ OAuth2 Resource Server + 刷新令牌轮换，而不是自造 session 或无状态裸 JWT

**做法**：见 6.1。用 Spring Security 的 resource-server 栈做验签与权限，刷新令牌哈希入库、逐次轮换、重用即撤族。

**优点**：REST 无状态水平扩展不需要共享 session 存储；HS256 对称密钥在"只有本服务签发和校验"的场景下最简单（无需公私钥分发）；轮换 + 族撤销把 refresh token 泄露的窗口压到最小；issuer/audience 校验把 token 的使用范围钉死，防跨服务重放。防枚举、锁定、身份一致性校验这些"安全细节完整度"在很多同类项目里是缺的。

**代价**：对称密钥意味着任何能验签的实例也能签发（不能把验签能力下放给不受信服务）；多实例必须共享同一个 `JWT_SECRET`——文档和代码都反复强调生产要显式配置。

### 8.10 Netty 独立 WebSocket 监听器，而不是 Spring WebSocket/复用 8080

**做法**：同 JVM 里起一个 Netty `ServerBootstrap` 监听 8090，`SmartLifecycle` 管启停；握手时复用 Spring 的 `JwtDecoder`。

**优点**：**线程模型彻底隔离**——成千上万的闲逛长连接由 Netty 的少量 EventLoop 承载，一个字节都不占用 Tomcat 的工作线程池；长连接的核心机制（心跳保活、空闲断开、帧大小上限、慢消费者跳过、token 到期主动断开）在 Netty 里是直接的原语。挂在同一进程里又免去了服务间调用——广播时直接查库取成员、往注册表里的 channel 写帧。TLS 终结留在 Nginx/Cloudflare，内部保持明文 ws，职责清晰。

**代价**：自己实现了一层握手鉴权与连接管理（但代码量很小，五个类）；WebSocket 状态在单机内存里（`ChannelRegistry`），多实例部署时需要按用户路由或引入跨实例广播——这也是它只作为"在线提示、不作为事实源"的原因之一。

### 8.11 聊天"REST 写、WS 只读"，而不是 WebSocket 双向业务

**做法**：消息创建只走 REST；WS 上行仅接受 ack 形状校验帧，不落库。

**优点**：把最难做对的部分（**写路径的幂等、鉴权、事务、一致性**）留在最成熟的 REST+DB 路径上，WS 只承担"在线加速"。断线、多端、乱序、重复推送全部有兜底（客户端按 message.id 去重、REST 拉历史补洞），**实时性是锦上添花而不是正确性前提**。这让整个聊天子系统的可靠性不依赖长连接的稳定性。

**代价**：发消息后对方收到有一拍异步延迟（outbox 轮询 100ms + 广播），对聊天场景完全够用；若追求百毫秒内直达可以演进为"事务提交后立即触发"的 afterCommit 提示。

### 8.12 推荐会话快照 + 签名 token + 全链路幂等，而不是"每次现算 + 普通分页"

**做法**：见 6.5。

**优点**：快照保证**翻页一致性**（用户刷到第 3 页不会看到第 1 页出现过的内容又冒出来）；HMAC 签名游标**防篡改、防跨用户、防过期重放**，服务端零存储（不用在服务端存分页状态）；曝光 token 把"行为数据可信"这个推荐系统的命门从协议层解决——没有可信的行为数据，再花哨的模型都是垃圾进垃圾出。请求级幂等（`clientRequestId` + 响应存储重放）让弱网重试安全，`events:batch` 的事件级去重（fingerprint 比对区分 duplicate 与 ID 重用攻击）同样严谨。

**代价**：快照全量存 JSON，内容库极大时需改为"存 top-K"或分层；实现复杂度是全项目最高的部分，好在测试覆盖也最厚（幂等、过期、混合排序各有专项测试）。

### 8.13 Ollama 本地嵌入 + 优雅降级，而不是调用云端嵌入 API

**做法**：语义能力封装在 `embedding/` 子包，默认关闭；开启后用本地 Ollama 跑 `qwen3-embedding:0.6b`。

**优点**：**内容数据不出本机**（爬取来的抖音元数据发第三方 API 有合规与隐私风险）；无 API 费用、无限额；断网/挂掉时整个推荐照常工作（词法+规则排序），AI 在这里是"增强"而不是"依赖"。增量索引（内容哈希比对）避免重复计算。

**代价**：本机要有 GPU/CPU 资源跑模型；模型质量与云端大模型有差距——但对"联想词 + 相似度加权"这个用途足够。

### 8.14 推送多厂商中立 + 数据库投递队列，而不是只接 FCM 或引入第三方推送 SaaS

**做法**：见 6.4。`PushProvider` 枚举 + `PushAdapter` SPI + 设备级路由。

**优点**：产品要进中国大陆市场，FCM 不可达是**已知且必然**的问题。提前把 provider 建模进设备表和投递状态机，接华为/小米/OPPO/vivo 时**只加适配器类，不动队列与状态机**。投递队列基于数据库实现（同 8.6 的取舍），退避、超时回收、设备禁用语义完整，比"收到通知就同步调 FCM"的朴素实现可靠一个量级。

**代价**：目前只有一个真实适配器，其余厂商是占位（但这是诚实的占位——显式 `PROVIDER_NOT_CONFIGURED`，绝不伪造成功）。

### 8.15 媒体 `storage_key` + 运行时拼 URL，而不是把完整 URL 写死进库

**做法**：见 6.2。

**优点**：域名迁移成本从"全表 UPDATE + 重新导入"降到"改一个环境变量"；配合 Nginx 对 `/media/` 的 Range 透传与一年 immutable 缓存头，本机 MinIO 就能提供接近 CDN 的行为，迁移到真 CDN 无缝。

**代价**：每次响应要拼字符串（开销可忽略）；库里保留的 `url` 字段仅作回退/调试，存在两份数据的心智负担。

### 8.16 JVM 时区固定 UTC + 时间戳无时区存储

**做法**：启动类静态块 `TimeZone.setDefault(UTC)`；schema 用 naive timestamp，JDBC 按 JVM 默认时区换算。

**优点**：存储值、API 时间、**游标编码的往返**三者在任何宿主机时区下都一致——键集分页的 score 比较依赖这一点，时区漂移会造成翻页错乱。这是"看似不起眼、出过事的人才知道值钱"的决策。

**代价**：无，纯收益；唯一要求是别在别处引入按本地时区的格式化。

### 8.17 Cloudflare Tunnel 做公网入口，而不是要求公网 IP/服务器

**做法**：`cloudflared` 容器以 quick tunnel（临时域名）或 named tunnel（固定域名 + token）把 Nginx 8088 暴露到公网；启动脚本自动把当前公网地址同步进 App 工程的 `gradle.properties`。

**优点**：开发机（哪怕在 NAT 后面）即可给真机 App 提供公网可达的 HTTPS + WSS 入口，**不需要买服务器、不需要备案、天然带 TLS**。临时隧道适合调试，命名隧道适合稳定联调。

**代价**：依赖 Cloudflare；quick tunnel 域名会变（脚本用"写回 App 配置 + 重建"消化了这一点）。

---

## 9. 贯穿全项目的可靠性主题：幂等

把第 5~8 章反复出现的"幂等"单独拎出来，因为它是本项目最一致的工程信条：**所有异步链路都是 at-least-once，所有副作用都靠数据库约束做到幂等，两者相加在效果上等于 exactly-once。**

| 环节 | 幂等键 | 载体 |
|---|---|---|
| 事件消费 | `(consumer_name, event_id)` | `consumer_processed_events` 主键 |
| 扇出分块 | chunkId = eventId + 序号 + 收件人指纹(SHA-256) | 同上（fanout consumer 名） |
| 通知生成 | `dedupe_key = eventId:type` | `notifications` 唯一索引 (receiver, dedupe_key) |
| 推送入队 | `notificationId + deviceId` 的确定性 UUID | `notification_deliveries` 幂等插入 |
| 点赞/收藏/关注 | 关系主键 | `content_likes` 等主键冲突 |
| 聊天消息 | `(conversation, sender, client_message_id)` | `chat_messages` 唯一索引 |
| 刷新令牌轮换 | CAS：`WHERE revoked_at IS NULL` | 影响行数 = 0 即检测重用 |
| 推荐读接口 | `(actor, endpoint, clientRequestId)` + 请求哈希 | `recommendation_client_requests`，可原样重放响应 |
| 推荐行为事件 | `(actor, event_id)` + 事件指纹 | 区分"重复上报"与"ID 被重用" |
| 内容导入 | 内容源 ID | 已存在则更新 |

配合每日清理任务控制这些表的膨胀，幂等成为可持续的属性而非慢性债务。

---

## 10. 现状短板与演进方向

诚实地说，这个项目也有清晰的"还没做完/可以更好"清单（`docs/technical-guide.md` 第 9 章有完整版，此处摘要）：

1. **Feed/搜索仍是内存分页**：`ContentService.feed()/videos()` 和 `SearchService.search()` 把全量内容载入内存再切片，数据量上万后是首要风险（关注流已经用了正确的键集分页，普通 Feed 还没有）。**这是上生产前必须改的第一件事。**
2. **卡片组装存在 N+1 查询**：每条内容单独查标签/推荐词/首资源，一页 20 条约 60~100 次 SQL，应改批量 `IN` 查询。
3. **上传接口缺大小/类型防护**：无 MIME 白名单与 multipart 上限，生产应改 MinIO 预签名直传。
4. **响应格式三套并存**：普通 DTO / 推荐 envelope / health 的 Map，缺全局 `@RestControllerAdvice` 统一异常。
5. **无缓存层**：读多写少的数据每次穿透到库，可引 Redis 缓存 Feed 卡片与未读数。
6. **群聊状态事件未实时广播**：解散/转让目前只落库发事件，`DomainEventProcessor` 对这两类事件暂不处理，客户端靠轮询感知。
7. **可观测性薄弱**：无 Actuator 指标/链路追踪，排障主要靠日志。
8. **Spring 本体未容器化**：部署依赖本机脚本，应补 Dockerfile 纳入 compose。

---

## 11. 术语表

| 术语 | 含义 |
|---|---|
| **Outbox** | 与业务数据同事务写入的本地事件表，后台异步投递，保证"业务成功 ⇒ 事件必达" |
| **扇出（fanout）** | 把一条新内容分发到所有关注者的 feed 表；分"写时扇出/读时合并"两种策略 |
| **键集分页（keyset pagination）** | 用"上一页最后一条的排序键"做 `WHERE (score,id) < (?,?)` 的分页，替代不稳定的 OFFSET |
| **FOR UPDATE SKIP LOCKED** | PostgreSQL 行锁语句：跳过已被别的事务锁住的行，多 Worker 拉任务互不阻塞 |
| **CAS 更新** | `UPDATE ... WHERE 状态=旧值` 并检查影响行数，行数=0 说明状态已被并发修改 |
| **会话快照（serve session）** | 推荐排序结果的不可变固化副本，翻页在快照内切片，保证顺序稳定 |
| **曝光 token（exposureToken）** | 服务端签发、绑定会话/内容/排名的 HMAC 令牌，行为上报必须出示，防伪造行为 |
| **刷新令牌轮换** | 每次刷新作废旧 refresh token 换发新的；旧 token 被重用即判定泄露、撤销整个令牌族 |
| **KRaft** | Kafka 去 ZooKeeper 的自治模式，本项目 Compose 里的单节点 Kafka 即此形态 |
| **storage_key** | 媒体文件在对象存储中的相对路径，运行时才拼公网 base URL |

---

## 12. 延伸阅读

| 想深入什么 | 读哪里 |
|---|---|
| 每个模块的实现细节与改进清单 | `docs/technical-guide.md`（同目录，与本文档互补） |
| 功能需求全貌 | `docs/flower-show-server-functional-requirements.md` |
| 认证接口契约 | `docs/auth-api.md` |
| 资料/社交接口契约 | `docs/profile-social-api.md` |
| 社交/Feed/通知契约与事件模型 | `docs/social-feed-notification-api.md` |
| 推荐接口契约 | `docs/recommendation-api.md` |
| 聊天与 WebSocket 契约 | `docs/chat-api.md` |
| 国内推送通道策略 | `docs/mainland-push-strategy.md` |
| 可视化架构图 | `docs/architecture/flower-show-server-architecture.html` |
| 数据库结构 | `src/main/resources/db/migration/V1~V14`（按序号即演进史） |
| 本地启动细节 | `README.md`、`scripts/start-flowershow.ps1` |

---

*本文档由源码通读整理；若代码后续演进，以最新代码与 `docs/technical-guide.md` 为准。*
