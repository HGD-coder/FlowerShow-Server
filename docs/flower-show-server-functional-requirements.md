# FlowerShow 服务端功能需求文档

版本：v1.0  
日期：2026-07-07  
适用范围：FlowerShow Android App 对应的服务端能力建设

## 1. 文档目标

本文档用于从功能视角定义 FlowerShow 服务端需要完成的需求。它不是先按接口、数据库、部署等技术维度拆分，而是先说明服务端要支持哪些业务功能，再为每个功能定义目标、流程、服务端职责、接口、数据、异常、权限和验收标准。

本文档后续可以继续拆分为：

- API 接口协议文档
- 数据库表结构与字段字典
- MediaCrawler 数据导入规范
- 搜索与推荐策略说明
- 部署运维与非功能需求
- 联调测试与验收用例

## 2. 背景与边界

FlowerShow 当前 App 技术方案以 Android 为主，客户端侧采用 Kotlin + Compose + MVI + Repository，播放侧使用 Media3 单播放器、缓存、预加载和多清晰度切换。服务端的职责不是替代客户端播放和 UI 状态管理，而是提供稳定的数据、资源、互动、搜索、通知、导入和账号能力。

当前服务端项目已经具备 Spring Boot 基础能力，包括 Feed、视频列表、搜索、用户、关注、评论、点赞、收藏、通知、上传、MediaCrawler JSONL 导入、作者账号生成等接口和数据表。后续正式化时，需要从 demo 形态升级为可供 App 长期联调和线上部署的服务。

### 2.1 服务端负责

- 内容元数据管理：视频、图片、图集、标题、描述、作者、标签、推荐词、统计数据。
- 媒体资源管理：上传、存储、访问 URL、多清晰度资源信息。
- 内容分发：推荐 Feed、视频列表、作者内容、关注流。
- 搜索与推荐：关键词搜索、猜你想搜、内容相关搜索词、热度排序、后续向量召回。
- 用户互动：点赞、收藏、评论、回复、关注。
- 通知：互动通知、关注通知、已读状态。
- 外部数据导入：MediaCrawler JSONL 导入、字段映射、去重、补全。
- 账号与权限：普通用户、作者、管理员、导入人员。
- 后台管理：内容、作者、评论、导入任务和数据统计。

### 2.2 客户端负责

- Compose 页面展示和导航。
- MVI 状态管理。
- Media3 播放器生命周期、起播、缓存、预加载、清晰度切换 UI。
- 本地搜索输入交互、搜索历史展示。
- App 端埋点触发和错误提示展示。

### 2.3 当前项目锚点

当前服务端已有以下主要接口：

- `GET /api/v1/feed`
- `GET /api/v1/videos`
- `GET /api/v1/search`
- `GET /api/v1/videos/{id}/recommend-words`
- `GET /api/v1/users`
- `GET /api/v1/users/{id}`
- `GET /api/v1/users/{id}/contents`
- `GET /api/v1/users/{id}/following-feed`
- `POST /api/v1/users/{id}/following/{targetUserId}`
- `DELETE /api/v1/users/{id}/following/{targetUserId}`
- `POST /api/v1/uploads`
- `POST /api/v1/contents`
- `POST /api/v1/contents/{contentId}/like`
- `POST /api/v1/contents/{contentId}/favorite`
- `GET /api/v1/contents/{contentId}/comments`
- `POST /api/v1/contents/{contentId}/comments`
- `POST /api/v1/comments/{commentId}/like`
- `GET /api/v1/users/{id}/notifications`
- `POST /api/v1/users/{id}/notifications/{notificationId}/read`
- `POST /api/v1/import/media-crawler-jsonl`
- `GET /api/v1/accounts/authors`
- `POST /api/v1/accounts/authors/generate`

## 3. 角色定义

| 角色 | 说明 | 主要功能 |
|-|-|-|
| 游客 | 未登录用户或未明确身份的访问者 | 浏览内容流、搜索、查看作者主页 |
| 普通用户 | App 登录用户 | 浏览、搜索、点赞、收藏、评论、关注、查看通知 |
| 作者 | 内容创作者或 MediaCrawler 导入生成的作者身份 | 查看自己的内容、后续登录作者后台、管理内容 |
| 管理员 | 平台管理人员 | 管理内容、用户、作者账号、评论、导入任务 |
| 导入人员 | 负责把 MediaCrawler 数据导入平台的人员或任务 | 上传/指定 JSONL 文件、执行导入、查看导入结果 |
| 系统任务 | 服务端异步任务或消息消费者 | 生成通知、分发事件、处理导入和后续推荐任务 |

## 4. 功能总览

| 编号 | 功能 | 目标 | 当前状态 | 优先级 |
|-|-|-|-|-|
| F01 | 首页内容流 | App 获取推荐内容混排列表 | 已有基础接口，需完善排序和分页元数据 | P0 |
| F02 | 视频播放资源 | 为客户端播放提供视频、封面、多清晰度 URL | 已有基础字段，需完善资源规范 | P0 |
| F03 | 搜索与猜你想搜 | 支持关键词搜索和推荐词 | 已有简单搜索，需完善策略 | P0 |
| F04 | 用户与作者主页 | 展示用户/作者资料和内容列表 | 已有基础接口 | P0 |
| F05 | 关注与关注流 | 关注作者并获取关注内容流 | 已有基础接口 | P1 |
| F06 | 点赞与收藏 | 支持内容互动和统计更新 | 已有基础接口 | P0 |
| F07 | 评论与回复 | 支持评论、回复、评论点赞 | 已有基础接口，需完善审核/删除 | P0 |
| F08 | 通知 | 用户查看互动通知并标记已读 | 已有基础接口，需完善事件来源 | P1 |
| F09 | 内容发布与上传 | 支持用户上传和发布内容 | 已有基础接口，需完善权限/审核/存储 | P1 |
| F10 | MediaCrawler 数据导入 | 将爬虫数据转为平台内容 | 已有导入接口，需形成规范 | P0 |
| F11 | 作者账号生成 | 为导入作者生成账号 | 已有基础接口，需完善安全 | P1 |
| F12 | 后台管理 | 管理内容、用户、评论和导入 | 需新增 | P2 |
| F13 | 事件与异步处理 | 解耦通知、统计、推荐等后续任务 | 已有 outbox 表，需接消息系统 | P2 |

优先级说明：

- P0：App 联调和核心演示必须具备。
- P1：完整业务闭环需要具备。
- P2：正式运营和长期扩展需要具备。

## 5. 功能需求详情

## F01 首页内容流

### 业务目标

用户打开 FlowerShow App 后，可以看到由视频、图文、图集组成的内容流。内容流需要支持分页加载，并返回客户端展示卡片所需的完整字段。

### 使用角色

- 游客
- 普通用户

### 前置条件

- 服务端已有可展示内容。
- 内容状态为已发布。
- 内容可见性允许当前用户访问。

### 用户操作流程

1. 用户进入 App 首页。
2. App 请求首页 Feed。
3. 服务端按推荐或时间顺序返回内容列表。
4. 用户滑动到底部时，App 请求下一页。
5. 服务端继续返回下一页内容。

### 服务端需要完成的事情

- 返回内容卡片列表。
- 支持视频、图片、图集混排。
- 支持分页参数 `page` 和 `pageSize`。
- 过滤未发布、不可见、已删除内容。
- 返回作者、封面、媒体资源、互动计数、标签、推荐词等展示字段。
- 为后续推荐排序预留扩展点。

### 接口需求

当前接口：

```text
GET /api/v1/feed?page=1&pageSize=10
```

建议响应从纯数组逐步升级为分页结构：

```json
{
  "items": [],
  "page": 1,
  "pageSize": 10,
  "hasMore": true,
  "nextPage": 2
}
```

### 数据需求

涉及数据：

- `content_items`
- `media_assets`
- `content_stats`
- `content_tags`
- `content_recommend_words`
- `users`

核心字段：

- 内容 ID、类型、标题、描述、作者、封面、状态、可见性、发布时间。
- 媒体 URL、媒体类型、清晰度、排序。
- 点赞数、评论数、收藏数、分享数、浏览数。
- 标签和推荐词。

### 异常情况

- 分页参数非法。
- 数据为空。
- 内容资源缺失。
- 数据库查询失败。

### 权限要求

- 公开内容允许游客访问。
- 私有或未发布内容不应进入公共 Feed。
- 后续如存在关注可见、作者可见等规则，需要在服务端统一过滤。

### 验收标准

- 请求第一页时返回不超过 `pageSize` 条内容。
- 内容卡片至少包含 `id`、`type`、`title`、`author`、`coverUrl` 或媒体 URL。
- 视频、图片、图集可以在同一个 Feed 中混排。
- 未发布内容不会出现在首页。
- 分页请求不会重复返回上一页数据，除非内容排序发生变化。

### 后续扩展

- 按用户兴趣推荐。
- 热度排序和时间排序切换。
- 内容冷启动推荐。
- Feed 曝光埋点和推荐反馈。

## F02 视频播放资源

### 业务目标

客户端播放视频时，服务端提供可播放的视频地址、封面、作者信息、互动计数和多清晰度资源列表。客户端负责 Media3 播放、缓存、预加载和清晰度切换；服务端负责资源元数据和访问地址。

### 使用角色

- 游客
- 普通用户

### 前置条件

- 视频内容已经导入或发布。
- 视频文件可通过 URL 访问。
- 服务端已保存视频资源和封面资源。

### 用户操作流程

1. 用户在首页滑动到视频内容。
2. App 从 Feed 返回字段中获取视频播放信息。
3. App 使用默认清晰度 URL 起播。
4. 如果存在多清晰度 URL，App 可展示清晰度切换入口。
5. App 根据自身策略决定自动或手动切换清晰度。

### 服务端需要完成的事情

- 为视频内容返回默认播放 URL。
- 返回封面图 URL。
- 返回多清晰度 URL 映射，例如 `360p`、`480p`、`720p`、`1080p`。
- 保存视频宽高、时长、文件大小、mime type 等资源元数据。
- 保证资源 URL 在客户端可访问。

### 接口需求

当前接口：

```text
GET /api/v1/videos?page=1&pageSize=10
GET /api/v1/feed?page=1&pageSize=10
```

建议后续补充内容详情接口：

```text
GET /api/v1/contents/{contentId}
```

视频字段建议：

```json
{
  "type": "video",
  "id": "video_001",
  "title": "月季开花记录",
  "videoUrl": "https://cdn.example.com/videos/video_001/video.mp4",
  "coverUrl": "https://cdn.example.com/videos/video_001/cover.jpg",
  "qualityUrls": {
    "1080p": "https://cdn.example.com/videos/video_001/video.mp4",
    "720p": "https://cdn.example.com/videos/video_001/video_720p.mp4",
    "480p": "https://cdn.example.com/videos/video_001/video_480p.mp4"
  }
}
```

### 数据需求

涉及数据：

- `content_items`
- `media_assets`

资源字段：

- `kind`
- `url`
- `storage_key`
- `mime_type`
- `file_size`
- `width`
- `height`
- `duration_ms`
- `quality`
- `sort_order`

### 异常情况

- 视频 URL 为空。
- 视频文件不存在。
- 清晰度 URL 部分缺失。
- 资源访问超时。
- 封面缺失。

### 权限要求

- 公开视频可直接访问。
- 私有视频资源需要鉴权访问或签名 URL。
- 管理员和作者可查看自己的未发布视频。

### 验收标准

- 视频内容返回的 `videoUrl` 可以被 Android 客户端播放。
- 如果存在 `qualityUrls`，至少包含一个可播放 URL。
- 封面图可正常加载。
- 缺失多清晰度时，客户端可回退到默认 `videoUrl`。

### 后续扩展

- 视频上传后自动转码。
- 对象存储和 CDN。
- HLS/DASH 自适应流。
- 视频审核、下架和防盗链。

## F03 搜索与猜你想搜

### 业务目标

用户可以通过关键词搜索 FlowerShow 内容，并在搜索页或视频详情中看到推荐搜索词。搜索结果需要覆盖标题、描述、作者、标签、推荐词和后续内容语义字段。

### 使用角色

- 游客
- 普通用户

### 前置条件

- 服务端有内容数据。
- 内容具备标题、描述、标签或推荐词。

### 用户操作流程

1. 用户进入搜索页。
2. App 展示猜你想搜、历史搜索或热门词。
3. 用户输入关键词并提交搜索。
4. 服务端返回匹配内容。
5. 用户点击内容进入播放或详情页。

### 服务端需要完成的事情

- 接收关键词。
- 查询标题、描述、作者、标签、推荐词。
- 对搜索结果排序。
- 支持空结果。
- 提供视频相关推荐词。
- 为后续向量召回、热度校正和 AI 模型预留扩展点。

### 接口需求

当前接口：

```text
GET /api/v1/search?keyword=rose
GET /api/v1/videos/{id}/recommend-words
```

建议补充：

```text
GET /api/v1/search/suggestions?keyword=rose
GET /api/v1/search/hot-words
```

### 数据需求

涉及数据：

- `content_items`
- `content_tags`
- `content_recommend_words`
- `content_stats`
- `users`

后续可扩展：

- `search_keywords`
- `search_logs`
- `content_embeddings`
- `content_search_terms`

### 排序需求

MVP 阶段：

1. 标题精确匹配优先。
2. 标签和推荐词匹配其次。
3. 描述和作者匹配再次。
4. 热度作为辅助排序。

后续阶段：

1. 文本分。
2. 向量相似度分。
3. 热度分。
4. 发布时间衰减。

### 异常情况

- 关键词为空。
- 关键词过长。
- 无搜索结果。
- 特殊字符导致查询异常。

### 权限要求

- 搜索结果只返回当前用户可见内容。
- 管理端搜索可包含未发布内容，但必须鉴权。

### 验收标准

- 搜索“玫瑰”时，标题、标签或推荐词包含“玫瑰”的内容能被召回。
- 搜索无结果时返回空列表，不报错。
- 推荐词接口能返回与指定视频相关的词。
- 搜索接口响应字段与 Feed 卡片字段保持兼容。

### 后续扩展

- 拼写纠错。
- 同义词扩展。
- 向量检索。
- 搜索日志和搜索质量回归样例集。
- AI 生成猜你想搜。

## F04 用户与作者主页

### 业务目标

用户可以查看平台用户或作者的资料信息，以及该用户发布的内容列表。MediaCrawler 导入的作者也应映射为平台用户。

### 使用角色

- 游客
- 普通用户
- 作者
- 管理员

### 前置条件

- 用户或作者已存在。
- 作者已经有关联内容。

### 用户操作流程

1. 用户点击内容卡片中的作者头像或昵称。
2. App 请求作者详情。
3. App 请求作者内容列表。
4. 用户可以继续浏览作者发布的内容。

### 服务端需要完成的事情

- 返回用户基础资料。
- 返回用户发布的内容列表。
- 区分 App 用户和外部来源作者。
- 支持 MediaCrawler 作者自动建档。
- 后续支持作者账号、作者后台和资料编辑。

### 接口需求

当前接口：

```text
GET /api/v1/users
GET /api/v1/users/{id}
GET /api/v1/users/{id}/contents
```

建议补充：

```text
PATCH /api/v1/users/{id}
GET /api/v1/authors/{id}
```

### 数据需求

涉及数据：

- `users`
- `content_items`
- `accounts`
- `follows`

用户字段：

- `id`
- `nickname`
- `avatar_url`
- `bio`
- `location`
- `source`
- `source_user_id`
- `created_at`

### 异常情况

- 用户不存在。
- 用户没有发布内容。
- 作者头像或外部来源数据缺失。

### 权限要求

- 公开资料允许游客查看。
- 用户资料编辑必须本人或管理员。
- 管理端可查看更多账号状态字段。

### 验收标准

- 可以通过用户 ID 获取用户详情。
- 可以通过用户 ID 获取该用户内容列表。
- MediaCrawler 导入数据能生成对应作者用户。
- 作者内容列表不包含未发布或不可见内容。

### 后续扩展

- 作者认证。
- 作者主页统计。
- 作者资料编辑。
- 作者内容管理后台。

## F05 关注与关注流

### 业务目标

用户可以关注感兴趣的作者，并查看已关注作者发布的内容流。

### 使用角色

- 普通用户

### 前置条件

- 用户已登录。
- 被关注对象存在。

### 用户操作流程

1. 用户进入作者主页。
2. 用户点击关注。
3. 服务端创建关注关系。
4. 用户进入关注流。
5. 服务端返回已关注作者的内容。
6. 用户可取消关注。

### 服务端需要完成的事情

- 创建关注关系。
- 取消关注关系。
- 防止用户关注自己。
- 防止重复关注导致重复数据。
- 返回关注作者内容流。
- 后续生成关注通知。

### 接口需求

当前接口：

```text
POST /api/v1/users/{id}/following/{targetUserId}
DELETE /api/v1/users/{id}/following/{targetUserId}
GET /api/v1/users/{id}/following-feed
```

### 数据需求

涉及数据：

- `follows`
- `users`
- `content_items`
- `notifications`
- `outbox_events`

### 异常情况

- 用户不存在。
- 被关注用户不存在。
- 用户关注自己。
- 重复关注。
- 取消不存在的关注关系。

### 权限要求

- 只能以当前登录用户身份关注或取消关注。
- 不能伪造其他用户 ID 进行关注操作。

### 验收标准

- 成功关注后，`following-feed` 可以返回被关注作者内容。
- 重复关注不会造成重复记录。
- 取消关注后，关注流不再包含该作者的新内容。
- 用户不能关注自己。

### 后续扩展

- 关注通知。
- 粉丝列表。
- 关注列表。
- 关注关系对推荐排序产生影响。

## F06 点赞与收藏

### 业务目标

用户可以对内容点赞和收藏，服务端需要记录互动关系并更新内容统计数据。

### 使用角色

- 普通用户

### 前置条件

- 用户已登录。
- 内容存在且可见。

### 用户操作流程

1. 用户点击内容卡片或详情页的点赞按钮。
2. 服务端记录点赞关系并更新点赞数。
3. 用户点击收藏按钮。
4. 服务端记录收藏关系并更新收藏数。
5. App 更新 UI 计数和状态。

### 服务端需要完成的事情

- 记录内容点赞。
- 记录内容收藏。
- 防止重复点赞和重复收藏导致计数错误。
- 更新 `content_stats`。
- 触发通知或 outbox 事件。
- 返回互动是否发生变化和最新统计。

### 接口需求

当前接口：

```text
POST /api/v1/contents/{contentId}/like
POST /api/v1/contents/{contentId}/favorite
```

当前请求体：

```json
{
  "userId": "user_001"
}
```

正式阶段建议从登录态获取用户 ID，而不是由客户端传入。

### 数据需求

涉及数据：

- `content_likes`
- `collections`
- `collection_items`
- `content_stats`
- `notifications`
- `outbox_events`

### 异常情况

- 内容不存在。
- 用户不存在。
- 重复点赞。
- 重复收藏。
- 统计更新失败。

### 权限要求

- 必须登录后才能点赞和收藏。
- 用户只能代表自己进行互动。

### 验收标准

- 首次点赞后点赞数加 1。
- 重复点赞不会重复加计数。
- 首次收藏后收藏数加 1。
- 重复收藏不会重复加计数。
- 点赞或收藏可以触发内容作者收到通知。

### 后续扩展

- 取消点赞。
- 取消收藏。
- 多收藏夹。
- 点赞列表和收藏列表。

## F07 评论与回复

### 业务目标

用户可以查看内容评论、发表评论、回复评论，并对评论点赞。

### 使用角色

- 游客
- 普通用户
- 管理员

### 前置条件

- 内容存在且允许评论。
- 发表评论和评论点赞需要用户登录。

### 用户操作流程

1. 用户打开内容评论区。
2. App 请求评论列表。
3. 用户输入评论并提交。
4. 服务端创建评论并更新评论数。
5. 用户回复某条评论。
6. 服务端保存 `parentId`。
7. 用户点赞评论。
8. 服务端更新评论点赞数。

### 服务端需要完成的事情

- 查询内容评论列表。
- 创建一级评论。
- 创建回复评论。
- 评论点赞。
- 更新内容评论数。
- 更新评论点赞数。
- 触发评论通知。
- 后续支持删除、审核、敏感词过滤。

### 接口需求

当前接口：

```text
GET /api/v1/contents/{contentId}/comments
POST /api/v1/contents/{contentId}/comments
POST /api/v1/comments/{commentId}/like
```

当前创建评论请求：

```json
{
  "userId": "user_001",
  "parentId": "comment_001",
  "body": "这朵花很好看"
}
```

### 数据需求

涉及数据：

- `comments`
- `comment_likes`
- `content_stats`
- `notifications`
- `outbox_events`

### 异常情况

- 内容不存在。
- 评论内容为空。
- 评论内容过长。
- 父评论不存在。
- 评论已删除或被隐藏。
- 重复点赞评论。

### 权限要求

- 游客可查看公开评论。
- 登录用户可发表评论和点赞评论。
- 作者或管理员可删除、隐藏评论。

### 验收标准

- 评论列表能返回评论者昵称、头像、正文、点赞数和时间。
- 创建评论后，内容评论数增加。
- 回复评论时保存父评论 ID。
- 重复点赞评论不会重复增加点赞数。

### 后续扩展

- 评论分页。
- 评论楼中楼。
- 评论删除。
- 评论审核。
- 敏感词过滤。
- 评论置顶。

## F08 通知

### 业务目标

当用户收到点赞、评论、回复、关注等互动时，服务端生成通知。用户可以查看通知列表并标记已读。

### 使用角色

- 普通用户
- 作者

### 前置条件

- 用户已登录。
- 有互动事件发生。

### 用户操作流程

1. 其他用户点赞、评论、回复或关注。
2. 服务端生成通知。
3. 用户进入消息页。
4. App 请求通知列表。
5. 用户点击通知或标记已读。
6. 服务端更新通知已读状态。

### 服务端需要完成的事情

- 根据互动事件生成通知。
- 保存通知接收人、触发人、类型、关联内容和评论。
- 查询用户通知列表。
- 标记单条通知已读。
- 后续支持全部已读、未读数量。

### 接口需求

当前接口：

```text
GET /api/v1/users/{id}/notifications
POST /api/v1/users/{id}/notifications/{notificationId}/read
```

建议补充：

```text
GET /api/v1/users/{id}/notifications/unread-count
POST /api/v1/users/{id}/notifications/read-all
```

### 数据需求

涉及数据：

- `notifications`
- `outbox_events`
- `users`
- `content_items`
- `comments`

通知类型建议：

- `content_liked`
- `content_favorited`
- `comment_created`
- `comment_replied`
- `comment_liked`
- `user_followed`

### 异常情况

- 通知不存在。
- 用户无权读取该通知。
- 重复标记已读。

### 权限要求

- 用户只能查看自己的通知。
- 管理员可在后台查看通知问题，但不应默认暴露私有消息。

### 验收标准

- 点赞、评论等事件可以生成通知。
- 用户可以获取自己的通知列表。
- 标记已读后，通知 `read` 状态变为 true。
- 用户不能读取其他用户通知。

### 后续扩展

- App 推送。
- 短信或邮件提醒。
- 通知聚合。
- 通知设置。
- RabbitMQ、Redis Stream 或 Kafka 消费通知事件。

## F09 内容发布与媒体上传

### 业务目标

用户或作者可以上传媒体文件并发布内容。服务端保存媒体资源，创建内容记录，并返回可展示的内容卡片。

### 使用角色

- 普通用户
- 作者
- 管理员

### 前置条件

- 用户已登录。
- 媒体文件符合上传限制。

### 用户操作流程

1. 用户选择图片或视频。
2. App 调用上传接口。
3. 服务端保存文件并返回资源 URL。
4. 用户填写标题、描述、标签等内容信息。
5. App 调用发布接口。
6. 服务端创建内容、媒体资源、标签和统计记录。
7. 内容进入已发布或待审核状态。

### 服务端需要完成的事情

- 接收 multipart 文件上传。
- 校验文件大小、类型和扩展名。
- 保存文件并返回 URL。
- 创建视频、图片或图集内容。
- 保存媒体资源排序。
- 保存标签和推荐词。
- 初始化内容统计。
- 后续支持审核状态。

### 接口需求

当前接口：

```text
POST /api/v1/uploads
POST /api/v1/contents
```

当前创建内容字段：

```json
{
  "type": "video",
  "authorUserId": "user_001",
  "title": "月季开花记录",
  "description": "花园里的月季开花了",
  "coverUrl": "https://example.com/cover.jpg",
  "visibility": "public",
  "mediaUrls": ["https://example.com/video.mp4"],
  "tags": ["月季", "花园"],
  "recommendWords": ["月季养护", "庭院花卉"]
}
```

正式阶段建议从登录态获取 `authorUserId`。

### 数据需求

涉及数据：

- `content_items`
- `media_assets`
- `content_tags`
- `content_recommend_words`
- `content_stats`
- `users`

### 异常情况

- 文件为空。
- 文件过大。
- 文件类型不支持。
- 内容标题为空。
- 作者不存在。
- 媒体资源保存失败。

### 权限要求

- 登录用户才能上传和发布。
- 作者只能编辑自己的内容。
- 管理员可管理所有内容。

### 验收标准

- 上传图片或视频后返回可访问 URL。
- 创建内容后可以在 Feed 或作者主页看到。
- 内容统计初始化为 0。
- 标签和推荐词可以随内容保存。

### 后续扩展

- 草稿箱。
- 内容审核。
- 视频转码。
- 封面自动截取。
- 图集排序编辑。
- 内容编辑和删除。

## F10 MediaCrawler 数据导入

### 业务目标

服务端支持把 MediaCrawler 爬取的抖音 JSONL 数据导入为 FlowerShow 内容，自动生成作者、内容、媒体资源、标签、推荐词和统计数据。

### 使用角色

- 导入人员
- 管理员
- 系统任务

### 前置条件

- JSONL 文件已存在于服务端可读取路径。
- 文件内容符合 MediaCrawler 记录格式。
- 相关媒体文件或 URL 可访问。

### 用户操作流程

1. 导入人员准备 MediaCrawler JSONL 文件。
2. 调用导入接口，传入文件路径和视频基础 URL。
3. 服务端逐行解析 JSONL。
4. 服务端按 `aweme_id` 或来源 ID 去重。
5. 服务端创建或更新作者。
6. 服务端创建或更新内容和媒体资源。
7. 服务端返回导入结果统计。

### 服务端需要完成的事情

- 读取 JSONL 文件。
- 逐行解析原始记录。
- 将 MediaCrawler 字段映射到平台字段。
- 根据来源 ID 去重。
- 创建用户/作者。
- 创建内容记录。
- 创建媒体资源。
- 写入标签和推荐词。
- 写入互动计数。
- 返回创建、更新、跳过、失败数量。

### 接口需求

当前接口：

```text
POST /api/v1/import/media-crawler-jsonl
```

当前请求字段：

```json
{
  "path": "data/douyin.jsonl",
  "videoBaseUrl": "http://localhost:8080/videos",
  "replaceMediaAssets": true
}
```

### 字段映射需求

| MediaCrawler 字段 | FlowerShow 字段 | 说明 |
|-|-|-|
| `aweme_id` | `content_items.id` 或 `source_user_id` 关联字段 | 视频唯一标识 |
| `title` / `desc` | `content_items.title` / `description` | 内容标题和描述 |
| `nickname` | `users.nickname` | 作者昵称 |
| `avatar` | `users.avatar_url` | 作者头像 |
| `cover_url` | `content_items.cover_url` | 封面 |
| `liked_count` | `content_stats.like_count` | 点赞数 |
| `comment_count` | `content_stats.comment_count` | 评论数 |
| `collected_count` | `content_stats.favorite_count` | 收藏数 |
| `share_count` | `content_stats.share_count` | 分享数 |
| `source_keyword` | `content_tags.tag` | 来源关键词或标签 |
| `quality_urls` | `media_assets.quality` + `url` | 多清晰度视频 |

### 数据需求

涉及数据：

- `users`
- `content_items`
- `media_assets`
- `content_tags`
- `content_recommend_words`
- `content_stats`

建议后续新增：

- `import_tasks`
- `import_task_logs`
- `source_records`

### 异常情况

- 文件不存在。
- 文件格式错误。
- 单行 JSON 解析失败。
- 必填字段缺失。
- 作者或内容写入失败。
- 媒体 URL 不可访问。

### 权限要求

- 只有管理员或导入人员可执行导入。
- 导入接口不应对普通用户开放。

### 验收标准

- 导入 100 条有效 MediaCrawler 记录后，服务端生成对应内容。
- 重复导入不会重复创建相同内容。
- 作者信息可以正确生成到用户表。
- 视频播放 URL 和封面 URL 可以被 App 使用。
- 导入结果返回创建、更新、失败数量。

### 后续扩展

- 导入任务异步执行。
- 导入进度查询。
- 导入失败明细下载。
- 自动下载媒体资源到对象存储。
- 自动生成推荐词和搜索向量。

## F11 作者账号生成

### 业务目标

为 MediaCrawler 导入的作者或平台作者生成可登录账号，为后续作者后台和内容管理做准备。

### 使用角色

- 管理员
- 作者

### 前置条件

- 作者用户已存在。
- 管理员具备账号生成权限。

### 用户操作流程

1. 管理员进入作者账号管理。
2. 管理员查看需要生成账号的作者列表。
3. 管理员设置默认密码或使用系统默认密码。
4. 服务端为作者创建账号。
5. 管理员将账号信息交付给作者。

### 服务端需要完成的事情

- 查询视频作者账号列表。
- 为没有账号的作者生成账号。
- 使用 BCrypt 保存密码 hash。
- 保证用户名唯一。
- 支持是否覆盖已有密码。
- 返回生成结果。

### 接口需求

当前接口：

```text
GET /api/v1/accounts/authors
POST /api/v1/accounts/authors/generate
```

当前请求字段：

```json
{
  "defaultPassword": "Flower@123456",
  "overwriteExistingPassword": false
}
```

### 数据需求

涉及数据：

- `accounts`
- `users`
- `content_items`

账号字段：

- `id`
- `user_id`
- `username`
- `password_hash`
- `role`
- `status`
- `generated`
- `created_at`
- `updated_at`

### 异常情况

- 用户不存在。
- 用户名冲突。
- 密码不符合安全要求。
- 重复生成账号。

### 权限要求

- 只有管理员可批量生成账号。
- 作者只能管理自己的账号信息。
- 密码 hash 不允许通过接口返回。

### 验收标准

- 可以查询作者账号列表。
- 可以为没有账号的作者生成账号。
- 重复生成时不会破坏已有账号，除非明确允许覆盖密码。
- 数据库中只保存密码 hash。

### 后续扩展

- 作者登录。
- 修改密码。
- 重置密码。
- 禁用账号。
- 作者后台内容管理。

## F12 后台管理

### 业务目标

管理员可以在后台管理内容、用户、作者账号、评论、导入任务和基础统计，保证平台内容可维护。

### 使用角色

- 管理员
- 导入人员

### 前置条件

- 管理员已登录。
- 管理端已接入服务端鉴权。

### 用户操作流程

1. 管理员登录后台。
2. 查看内容列表、用户列表、评论列表或导入任务。
3. 管理员对内容进行编辑、下架、删除或恢复。
4. 管理员处理评论。
5. 管理员查看导入结果和平台统计。

### 服务端需要完成的事情

- 提供管理端内容列表。
- 支持按状态、作者、类型、关键词筛选。
- 支持内容下架、恢复和删除。
- 支持评论隐藏或删除。
- 支持用户和作者账号管理。
- 支持导入任务记录查询。
- 支持基础数据统计。

### 接口需求

建议新增：

```text
GET /api/v1/admin/contents
PATCH /api/v1/admin/contents/{contentId}
DELETE /api/v1/admin/contents/{contentId}
GET /api/v1/admin/users
GET /api/v1/admin/comments
PATCH /api/v1/admin/comments/{commentId}
GET /api/v1/admin/import-tasks
GET /api/v1/admin/stats
```

### 数据需求

涉及数据：

- 所有业务表。
- 后续新增管理审计表：
  - `admin_audit_logs`
  - `import_tasks`
  - `import_task_logs`

### 异常情况

- 管理员权限不足。
- 内容不存在。
- 内容状态冲突。
- 删除操作不可恢复。

### 权限要求

- 所有后台接口必须鉴权。
- 高风险操作需要记录审计日志。
- 删除类操作应优先软删除。

### 验收标准

- 管理员可以分页查看内容和用户。
- 管理员可以下架内容。
- 被下架内容不会出现在 App Feed 和搜索结果中。
- 管理员操作有审计记录。

### 后续扩展

- 可视化后台页面。
- 操作审批。
- 批量处理。
- 数据看板。

## F13 事件与异步处理

### 业务目标

将通知、统计、推荐、导入等非同步主流程任务通过事件机制解耦，避免控制器和业务服务直接耦合具体消息系统。

### 使用角色

- 系统任务
- 开发人员

### 前置条件

- 业务服务在关键操作后发布领域事件。
- 服务端存在 outbox 或消息队列机制。

### 业务流程

1. 用户点赞、评论、收藏、关注或发布内容。
2. 服务端完成主业务写入。
3. 服务端记录 outbox 事件。
4. 后台任务扫描待处理事件。
5. 后续消费者生成通知、更新统计、刷新推荐或发送消息。

### 服务端需要完成的事情

- 定义领域事件类型。
- 在业务操作成功后写入 outbox。
- 后台任务处理 pending 事件。
- 记录重试次数、处理时间和失败原因。
- 后续支持 RabbitMQ、Redis Stream 或 Kafka。

### 数据需求

涉及数据：

- `outbox_events`
- `notifications`
- 后续消息系统。

事件类型建议：

- `content.created`
- `content.liked`
- `content.favorited`
- `comment.created`
- `comment.liked`
- `user.followed`
- `import.completed`

### 异常情况

- 事件写入失败。
- 事件处理失败。
- 重复消费。
- 消息队列不可用。

### 权限要求

- 事件处理为系统内部能力，不对普通用户开放。
- 管理员可查看失败事件和重试状态。

### 验收标准

- 点赞或评论后可以生成 outbox 事件。
- outbox 事件处理成功后状态变为 processed。
- 处理失败时记录错误并可重试。
- 业务主流程不直接依赖具体消息队列实现。

### 后续扩展

- 接入 RabbitMQ。
- 事件幂等消费。
- 死信队列。
- 消息监控和告警。

## 6. 统一非功能需求

虽然本文档以功能为主，但所有功能需要满足以下统一要求。

### 6.1 鉴权与安全

- 正式环境不能依赖客户端传入 `userId` 表示身份。
- 登录后应通过 JWT、Session 或其他机制识别当前用户。
- 管理接口必须限制管理员访问。
- 密码必须使用安全 hash，不允许明文保存。
- 上传接口需要限制文件类型、大小和路径。
- 导入接口不能暴露给普通用户。

### 6.2 数据库

- 本地开发可继续使用 H2。
- 正式环境建议使用 PostgreSQL。
- 表结构变更必须通过 Flyway migration 管理。
- 重要查询字段需要索引。
- 统计数据更新需要避免重复计数。

### 6.3 响应格式

建议统一响应结构：

```json
{
  "success": true,
  "data": {},
  "message": null
}
```

错误响应建议包含：

```json
{
  "success": false,
  "data": null,
  "message": "content not found",
  "code": "CONTENT_NOT_FOUND"
}
```

### 6.4 日志与监控

- 记录接口错误日志。
- 记录导入任务日志。
- 记录上传失败日志。
- 记录 outbox 事件失败日志。
- 后续接入指标监控和告警。

### 6.5 性能

- Feed、搜索、评论列表必须支持分页。
- 上传和导入不应阻塞核心浏览接口。
- 搜索后续需要评估响应时间和相关性。
- 视频资源建议走对象存储和 CDN。

## 7. MVP 范围建议

第一阶段建议完成以下功能，作为 App 和服务端稳定联调的 MVP：

1. F01 首页内容流
2. F02 视频播放资源
3. F03 搜索与猜你想搜
4. F04 用户与作者主页
5. F06 点赞与收藏
6. F07 评论与回复
7. F10 MediaCrawler 数据导入

第二阶段补齐完整用户闭环：

1. F05 关注与关注流
2. F08 通知
3. F09 内容发布与媒体上传
4. F11 作者账号生成

第三阶段面向正式运营：

1. F12 后台管理
2. F13 事件与异步处理
3. PostgreSQL、RabbitMQ、对象存储、CDN、监控和权限体系

## 8. 待确认问题

以下问题需要在进入详细接口设计前确认：

1. App 是否需要登录后才能点赞、收藏和评论，还是 demo 阶段继续允许传 `userId`？
2. 内容类型是否固定为 `video`、`image`、`album` 三类？
3. MediaCrawler 导入的视频文件是保留本地 HTTP 服务，还是迁移到对象存储/CDN？
4. 搜索第一阶段是否只做数据库文本匹配，还是直接接入向量召回？
5. 收藏是否只需要默认收藏夹，还是需要用户自定义收藏夹？
6. 评论是否需要审核和删除能力？
7. 作者账号是否真的需要登录后台，还是只作为数据管理用途？
8. 后台管理是否需要独立前端页面？
9. 正式环境是否确定使用 PostgreSQL + RabbitMQ？
10. 视频多清晰度是继续多 MP4 URL，还是计划切换到 HLS/DASH？
