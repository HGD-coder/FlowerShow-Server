# Flower Show Server

REST backend for the Flower Show Android app.

## System Architecture

![System architecture diagram](docs/architecture/flower-show-server-architecture.visual-check.2048x1320.light.png)

GitHub shows the static preview above. For the interactive version, [download the HTML file](docs/architecture/flower-show-server-architecture.html) and open it locally.

## Requirements

- JDK 17+
- Maven 3.9+
- PostgreSQL 16+ with pgvector and Kafka 4+, or Docker

The application uses PostgreSQL by default. Flyway runs database migrations at startup.

## Local Database

Start the local infrastructure with Docker:

```bash
docker compose up -d kafka postgres minio minio-init nginx
docker compose ps
```

The Compose PostgreSQL image includes pgvector. Semantic search is optional and
uses a local Ollama container with `qwen3-embedding:0.6b` by default:

```powershell
docker compose --profile semantic up -d ollama
docker compose --profile semantic run --rm --no-deps ollama-model
```

Default connection:

```text
jdbc:postgresql://localhost:5432/flower_show
user: flower_show
password: flower_show
```

Override the connection with environment variables:

```bash
DB_URL=jdbc:postgresql://localhost:5432/flower_show
DB_USERNAME=flower_show
DB_PASSWORD=flower_show
```

Authentication uses signed JWT access tokens. Set a production secret with at least
32 UTF-8 bytes:

```powershell
$env:JWT_SECRET = 'replace-with-a-long-random-production-secret'
```

When `JWT_SECRET` is absent during local development, the server creates
`data/jwt-secret.local`. That file is ignored by Git and reused across restarts.

## Run

The supported one-command startup path starts the Spring Boot application behind
the Nginx gateway:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-flowershow.ps1
```

The script:

- starts PostgreSQL, Kafka, MinIO, and Nginx;
- validates and reloads Nginx, then starts the configured or temporary
  Cloudflare Tunnel;
- uses JDK 17 to build and start the Spring service on host port `8080`;
- waits for the Spring service's local and public gateway health checks;
- derives one public gateway URL for API and media, and synchronizes it to the
  Android project's `gradle.properties`.

Enable hybrid vector search and semantic personalization with the same startup
path:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-flowershow.ps1 `
  -EnableSemanticSearch
```

On the first run, Docker downloads Ollama and the embedding model. Later starts
reuse the local model volume. If Ollama is unavailable, the Spring service stays
up and automatically uses the existing database keyword and rule-based ranking.
When overriding `EMBEDDING_MODEL`, the startup script uses the same value for
`OLLAMA_MODEL` unless `OLLAMA_MODEL` is set explicitly.

Nginx on `8088` is the only App/Cloudflare API entry point. The same Spring
service handles all `/api/` routes, including these four recommendation
endpoints:

- `POST /api/v1/feed/pages`
- `POST /api/v1/search/guesses`
- `POST /api/v1/search/videos`
- `POST /api/v1/events:batch`

Legacy `GET /api/v1/search` and the rest of the existing API use that same
service. Spring remains responsible for PostgreSQL, JWT, content, social
features, interactions, notifications, and recommendation state.
`events:batch` is recommendation feedback only; it does not replace Spring's
durable interaction APIs.

The Spring backend PID file and timestamped stdout/stderr logs are written to
`target/runtime`. A failed service health check prints the corresponding log
tails. On restart, the script only stops an existing `8080` listener when its
command line identifies the Flower Show Spring backend.

Use the build-skip switch only after the Spring artifact already exists:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-flowershow.ps1 `
  -SkipServerBuild
```

To also build and install the debug App on a connected phone:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-flowershow.ps1 `
  -SkipServerBuild `
  -InstallApp
```

Without a named tunnel, the script uses a Quick Tunnel. A new Quick Tunnel URL
requires rebuilding the App because the gateway is compiled into the APK.

Kafka mode is the normal integration environment:

```powershell
$env:KAFKA_ENABLED = 'true'
$env:LOCAL_EVENT_DISPATCHER_ENABLED = 'false'
$env:KAFKA_BOOTSTRAP_SERVERS = 'localhost:9092'
$env:MEDIA_PUBLIC_BASE_URL = 'http://localhost:8088/media'
mvn spring-boot:run
```

For a backend-only IDEA session without Kafka, omit those variables. The same
Outbox events are consumed by the local dispatcher so feature development still
forms a complete loop.

```bash
mvn spring-boot:run
```

The REST server listens on `http://localhost:8080`. The same Spring Boot process
also starts the internal Netty chat WebSocket listener on
`ws://localhost:8090/ws/chat`.

### Realtime chat WebSocket

The default topology keeps TLS and public ingress at Nginx/Cloudflare while the
Spring-managed Netty listener remains plain internal WebSocket:

```text
App -- wss://public-host/ws/chat --> Cloudflare/Nginx :8088
                                      |
                                      +-- ws://host.docker.internal:8090/ws/chat
                                          (same Spring Boot process as REST :8080)
```

External clients must use `wss://{public-host}/ws/chat` when the gateway is
published over HTTPS. `ws://host.docker.internal:8090/ws/chat` is the internal
Nginx-to-Netty hop; port `8090` should not be exposed as a public TLS endpoint.
For direct local diagnostics, use `ws://localhost:8090/ws/chat`.

The listener uses the following unified Spring configuration and environment
overrides:

```yaml
flower-show:
  chat:
    websocket:
      enabled: true
      port: 8090
      path: /ws/chat
      idle-timeout: 60s
      max-frame-payload-length: 8192
```

```powershell
$env:CHAT_WEBSOCKET_ENABLED = 'true'
$env:CHAT_WEBSOCKET_PORT = '8090'
$env:CHAT_WEBSOCKET_PATH = '/ws/chat'
$env:CHAT_WEBSOCKET_IDLE_TIMEOUT = '60s'
$env:CHAT_WEBSOCKET_MAX_FRAME_PAYLOAD_LENGTH = '8192'
```

Nginx currently proxies the fixed public route `/ws/chat` to internal port
`8090`; changing the path or port also requires the matching Nginx route change.
Normal tests disable the listener. The focused gateway integration test enables
it with port `0` so the operating system chooses an available port.

The Upgrade request must include `Authorization: Bearer {accessToken}`. The
gateway uses the same `JwtDecoder` as REST, including signature, issuer, audience,
and expiry validation, and uses JWT `sub` as the user ID. The gateway closes the
channel with code `4001` when that access token expires so the App reconnects
through its normal token-refresh path. A successful connection receives:

```json
{"type":"connection.ready"}
```

Message creation remains a REST write. WebSocket never accepts a business-message
write; its only text input is an ACK such as:

```json
{"type":"ack","eventId":"evt_..."}
```

ACKs are validated but not persisted. Server Ping, client Pong, close handling,
the configured idle timeout, and the maximum frame size bound connection
resources. Each user may hold multiple concurrent channels, and all of their
devices receive the same event:

```json
{
  "type": "chat.message.created",
  "eventId": "evt_...",
  "occurredAt": "2026-07-30T04:00:00Z",
  "message": {
    "id": "msg_...",
    "conversationId": "con_...",
    "senderUserId": "user-a",
    "senderNickname": "Alice",
    "senderAvatarUrl": null,
    "type": "text",
    "text": "hello",
    "sharedContent": null,
    "clientMessageId": "android-local-42",
    "createdAt": "2026-07-30T04:00:00Z"
  }
}
```

`message` has exactly the REST `ChatMessageDto` shape. Delivery is an online
hint, not a second message store: clients deduplicate by `message.id` and use the
existing REST history endpoint to recover gaps. PostgreSQL remains the source of
truth. Both the local dispatcher and Kafka listener reach the same
`DomainEventProcessor`; the default reliable Outbox poll interval is `100ms`, and
neither Kafka nor Netty is part of the synchronous REST send path.

For direct diagnostics, Spring listens on `http://localhost:8080` and the
unified local gateway listens on `http://localhost:8088`. Android emulator
clients should use `http://10.0.2.2:8088/` when they are not using the
synchronized public gateway.

When media is served by Nginx, MinIO, Cloudflare Tunnel, or a real CDN, start the server with the public media base URL:

```powershell
$env:MEDIA_PUBLIC_BASE_URL = 'https://your-public-domain.example.com/media'
mvn spring-boot:run
```

For media rows that have `storage_key`, API responses build media URLs from `MEDIA_PUBLIC_BASE_URL` at request time. This lets the public domain change without rewriting database rows.

Flyway migrations create users, accounts, refresh tokens, content, media assets,
comments, likes, favorites, follows, social counters, hybrid feed entries,
notifications, device tokens, push delivery jobs, consumer deduplication records,
durable direct/group conversations and messages, and outbox events.

Tests still use an in-memory H2 database in PostgreSQL compatibility mode so they can run without a local PostgreSQL server.

## Multi-channel push (phase 1)

PostgreSQL `notifications` and `notification_deliveries` remain the source of
truth for notification history and delivery state. Remote push is disabled by
default. With `PUSH_ENABLED=false`, in-app notifications and the existing
Outbox/local/Kafka paths behave as before, and one delivery row is still
accumulated per enabled device. No provider credentials are loaded and no
remote delivery worker runs.

Phase 1 adds a provider-neutral device and Worker contract plus the real FCM
adapter. The accepted providers are `fcm`, `huawei`, `xiaomi`, `oppo`, and
`vivo`, but only FCM has a network adapter today. Huawei, Xiaomi, OPPO, and vivo
registrations can be stored for forward compatibility; their deliveries become
`dead` with `<PROVIDER>_PROVIDER_NOT_CONFIGURED`, and the device remains enabled.
No vendor network call or credential is fabricated.

To enable the FCM adapter on Windows, keep the service-account JSON outside this
repository and configure Google Application Default Credentials (ADC) in the
PowerShell session that starts Spring:

```powershell
$env:GOOGLE_APPLICATION_CREDENTIALS = 'C:\secure\firebase-service-account.json'
$env:FIREBASE_PROJECT_ID = 'your-firebase-project-id'
$env:PUSH_ENABLED = 'true'

mvn spring-boot:run
```

`FIREBASE_PROJECT_ID` may be omitted when ADC can infer the target project. Set
it explicitly when the credential project and target Firebase project differ.
The Firebase Cloud Messaging API must be enabled and the credential must have
permission to send to the target project. Never copy the service-account JSON
into the repository or print credentials, device registration values, FIDs, or
Authorization headers in logs.

The App registers a recipient after login through `POST /api/v1/me/devices`.
The existing request remains compatible and defaults to provider `fcm` and
recipient type `token`:

```json
{
  "token": "fcm-registration-token",
  "platform": "android",
  "deviceName": "Pixel 8"
}
```

Provider-specific metadata is optional:

```json
{
  "token": "vendor-registration-token",
  "platform": "android",
  "deviceName": "Mate 70",
  "recipientType": "token",
  "pushProvider": "huawei",
  "deviceBrand": "Huawei",
  "appPackage": "com.example.flowershow"
}
```

For an FCM Firebase Installation ID (FID), the request field remains named
`token` for wire compatibility and adds the type:

```json
{
  "token": "firebase-installation-id",
  "recipientType": "fid",
  "pushProvider": "fcm",
  "platform": "android",
  "deviceName": "Pixel 8"
}
```

Only FCM accepts `recipientType=fid`; mainland providers require `token`.
Responses and device lists include `recipientType`, `pushProvider`,
`deviceBrand`, and `appPackage`, but never return the token/FID. Firebase Admin
Java SDK 9.10.0 sends FIDs through `setFid`; legacy registration tokens continue
through the deprecated but compatible `setToken` path.

Push worker configuration:

| Environment variable | Default | Meaning |
| --- | --- | --- |
| `PUSH_ENABLED` | `false` | Starts the remote delivery worker only when `true`. |
| `PUSH_PROVIDER` | ignored | Deprecated compatibility variable; routing now uses each device row's provider. |
| `FIREBASE_PROJECT_ID` | empty | Optional target Firebase project ID. |
| `PUSH_BATCH_SIZE` | `100` | Maximum rows claimed per worker cycle. |
| `PUSH_SCHEDULE_INTERVAL` | `5s` | Delay between worker cycles. |
| `PUSH_MAX_ATTEMPTS` | `8` | Claim/send attempts before a delivery becomes `dead`. |
| `PUSH_BASE_BACKOFF` | `5s` | First retry delay. |
| `PUSH_MAX_BACKOFF` | `15m` | Exponential retry-delay cap. |
| `PUSH_CLAIM_TIMEOUT` | `2m` | Time before an abandoned `processing` claim is recoverable. |
| `PUSH_MESSAGE_MAX_AGE` | `24h` | Maximum notification age eligible for remote delivery. |
| `PUSH_ANDROID_CHANNEL_ID` | `flower_show_notifications` | Stable Android notification channel ID. |
| `PUSH_NOTIFICATION_TITLE` | `Flower Show` | Notification payload title. |

The Android App must create a channel whose ID matches
`PUSH_ANDROID_CHANNEL_ID`. Each FCM message carries both a notification
`title`/`body` and data fields containing at least `notificationId` and `type`;
available `actorUserId`, `contentId`, and `commentId` values are included. FCM
credentials are still loaded lazily only when an FCM delivery is actually sent.

Workers claim due `pending`/`retry` rows in short
`FOR UPDATE SKIP LOCKED` transactions, perform network calls after the
transaction commits, then update only a matching claim owner. Timed-out claims
are recovered, old workers cannot overwrite a newer lease, retries use bounded
exponential backoff, and notifications older than `PUSH_MESSAGE_MAX_AGE` become
`dead`. `UNREGISTERED` and `SENDER_ID_MISMATCH` also disable the device and end
its queued deliveries; permanent parameter/credential/authentication failures
do not disable the device.

The target channel strategy is: PostgreSQL in-app history as the durable fact
source; online WebSocket delivery as a near-term priority that is not yet
implemented; Huawei/Xiaomi/OPPO/vivo or an approved aggregation channel for
offline mainland-China devices; and FCM for GMS-capable or overseas devices.
See [docs/mainland-push-strategy.md](docs/mainland-push-strategy.md). This phase
does not implement WebSocket, chat delivery, or mainland vendor adapters.

## MediaCrawler Data Import

The Android app currently keeps metadata in assets:

```text
D:\android-studio\flowershow\app\src\main\assets\video_data.jsonl
D:\android-studio\flowershow\app\src\main\assets\image_data.jsonl
```

The media files are stored separately under:

```text
D:\MediaCrawler\MediaCrawler\data\douyin
```

For local device testing, serve media through the Nginx gateway on `8088`. The current imported development base URL is:

```text
http://10.135.0.166:8088/media
```

The import stores relative media paths such as `videos/{id}/video.mp4` and `images/{id}/000.jpeg` in `media_assets.storage_key`. It also keeps a concrete `url` value for fallback/debugging. Runtime API responses prefer:

```text
MEDIA_PUBLIC_BASE_URL + "/" + storage_key
```

Import video and image metadata into PostgreSQL:

```powershell
$baseUrl = 'http://10.135.0.166:8088/media'

$videoBody = @{
  path = 'D:\android-studio\flowershow\app\src\main\assets\video_data.jsonl'
  assetBaseUrl = $baseUrl
  replaceMediaAssets = $true
} | ConvertTo-Json

Invoke-RestMethod `
  -Uri 'http://localhost:8080/api/v1/import/media-crawler-jsonl' `
  -Method Post `
  -ContentType 'application/json; charset=utf-8' `
  -Body $videoBody

$imageBody = @{
  path = 'D:\android-studio\flowershow\app\src\main\assets\image_data.jsonl'
  assetBaseUrl = $baseUrl
  replaceMediaAssets = $true
} | ConvertTo-Json

Invoke-RestMethod `
  -Uri 'http://localhost:8080/api/v1/import/media-crawler-jsonl' `
  -Method Post `
  -ContentType 'application/json; charset=utf-8' `
  -Body $imageBody
```

When the real CDN domain is available, upload `videos/` and `images/` to the CDN origin and rerun the same import with:

```powershell
$baseUrl = 'https://your-cdn-domain.example.com'
```

The import is idempotent by content id and rewrites media asset URLs when `replaceMediaAssets` is true.

After `storage_key` has been populated once, changing from local Nginx to Cloudflare Tunnel or a real CDN only requires restarting the backend with a new `MEDIA_PUBLIC_BASE_URL`. Re-import is only needed for old databases that do not yet have `storage_key`, or when the underlying media file list changes.

## External Access

For quick external testing, start the Cloudflare Quick Tunnel service:

```powershell
docker compose --profile tunnel up -d cloudflared
docker compose --profile tunnel logs --no-color --tail=120 cloudflared
```

The log prints a temporary public URL. Treat that generated value as
`$publicGateway`; do not store it in source files because it changes when the
Quick Tunnel is replaced.

```text
$publicGateway = https://<generated-subdomain>.trycloudflare.com
```

Use that URL as the public gateway:

```text
API:
$publicGateway/api/v1/feed

Media:
$publicGateway/media/videos/{id}/video.mp4
```

Start or restart the backend with the tunnel media base URL:

```powershell
$env:MEDIA_PUBLIC_BASE_URL = "$publicGateway/media"
mvn spring-boot:run
```

Quick Tunnel URLs are temporary and can change after the tunnel restarts. The
startup script detects the current URL and updates:

```text
D:\android-studio\flowershow\gradle.properties
```

For a stable endpoint that does not require rebuilding the App after a server
restart:

1. Create a named Cloudflare Tunnel and a public hostname such as
   `api.example.com`.
2. Set the published application service to `http://nginx:8088`.
3. Copy `.env.example` to `.env` and set both the public hostname and tunnel token.
4. Run `scripts\start-flowershow.ps1` once with `-InstallApp`.

The script then starts the `cloudflared-named` Compose service. On subsequent
starts the hostname remains unchanged, so the installed App can be used without
another build. `.env` is ignored by Git because the tunnel token grants access to
the tunnel and must remain secret.

The Nginx gateway blocks public access to `POST /api/v1/import/*`; imports should be run locally against `http://localhost:8080`.

## Adaptive HLS With MP4 Fallback

Progressive MP4 remains available for old App versions and manual quality selection.
Multi-quality MP4 rows are stored in `media_assets` with:

```text
kind = video
delivery_type = progressive
container_format = mp4
quality = 1080p, 720p, 480p, 360p, etc.
```

The HLS pipeline creates an additional parallel row without replacing those MP4 rows:

```text
kind = video
delivery_type = hls
container_format = hls
storage_key = videos/{id}/hls/master.m3u8
quality = auto
```

Convert selected videos with the local NVIDIA GPU, upload the generated two-second
VOD segments to MinIO, and register them in PostgreSQL:

```powershell
.\scripts\convert-videos-to-hls.ps1 -ContentId 7641521574747072625
```

Multiple IDs can be comma-separated. Batch conversion is deliberately explicit:

```powershell
# Convert a controlled first wave.
.\scripts\convert-videos-to-hls.ps1 -All -MaxVideos 10

# Convert every source video only after checking storage capacity.
.\scripts\convert-videos-to-hls.ps1 -All
```

Use `-Force` to rebuild existing HLS output, or `-Encoder x264` when NVENC is not
available. The API contract is backward compatible:

```text
videoUrl    = progressive MP4 fallback
qualityUrls = manually selectable MP4 renditions
hlsUrl      = adaptive HLS master playlist, when generated
```

The Android App uses `hlsUrl` in Auto mode and lets Media3 adapt among renditions.
Manual quality selection still switches to an MP4 URL. Videos without `hlsUrl`
continue through the existing progressive path.

## API

```text
GET    /api/v1/health
GET    /api/v1/feed?page=1&pageSize=10
GET    /api/v1/videos?page=1&pageSize=10
GET    /api/v1/search?keyword=garden
GET    /api/v1/videos/{id}/recommend-words

POST   /api/v1/auth/register
POST   /api/v1/auth/login
POST   /api/v1/auth/refresh
POST   /api/v1/auth/logout
POST   /api/v1/auth/logout-all
GET    /api/v1/auth/me

GET    /api/v1/me
PATCH  /api/v1/me
GET    /api/v1/me/contents?tab=posts|liked|favorites&page=1&pageSize=20
GET    /api/v1/me/notifications?cursor=&pageSize=20
GET    /api/v1/me/notifications/unread-count
POST   /api/v1/me/notifications/{notificationId}/read
POST   /api/v1/me/notifications/read-all
GET    /api/v1/me/devices
POST   /api/v1/me/devices
DELETE /api/v1/me/devices/{deviceId}

GET    /api/v1/feed/following?cursor=&pageSize=20

GET    /api/v1/users
GET    /api/v1/users/{id}
GET    /api/v1/users/{id}/profile
PATCH  /api/v1/users/{id}/profile
GET    /api/v1/users/{id}/profile/contents?tab=posts|liked|favorites&page=1&pageSize=20
PATCH  /api/v1/users/{id}/contents/{contentId}/profile-display
GET    /api/v1/users/{id}/contents
GET    /api/v1/users/{id}/following-feed
GET    /api/v1/users/{id}/followers?keyword=&page=1&pageSize=20
GET    /api/v1/users/{id}/following?keyword=&page=1&pageSize=20
POST   /api/v1/users/{id}/following/{targetUserId}
DELETE /api/v1/users/{id}/following/{targetUserId}
GET    /api/v1/users/{id}/following/{targetUserId}/preferences
PATCH  /api/v1/users/{id}/following/{targetUserId}/preferences

POST   /api/v1/uploads
POST   /api/v1/contents
POST   /api/v1/contents/{contentId}/like
POST   /api/v1/contents/{contentId}/favorite

GET    /api/v1/contents/{contentId}/comments
POST   /api/v1/contents/{contentId}/comments
POST   /api/v1/comments/{commentId}/like

GET    /api/v1/users/{id}/notifications
POST   /api/v1/users/{id}/notifications/{notificationId}/read

GET    /api/v1/chat/conversations?cursor=&pageSize=20
POST   /api/v1/chat/conversations/direct
POST   /api/v1/chat/conversations/group
GET    /api/v1/chat/conversations/{conversationId}
PATCH  /api/v1/chat/conversations/{conversationId}
POST   /api/v1/chat/conversations/{conversationId}/members
DELETE /api/v1/chat/conversations/{conversationId}/members/{userId}
POST   /api/v1/chat/conversations/{conversationId}/owner
POST   /api/v1/chat/conversations/{conversationId}/dissolve
POST   /api/v1/chat/conversations/{conversationId}/leave
GET    /api/v1/chat/conversations/{conversationId}/messages?cursor=&pageSize=50
POST   /api/v1/chat/conversations/{conversationId}/messages
POST   /api/v1/chat/conversations/{conversationId}/read
```

Public profile reads accept an optional Bearer JWT so the response can include the
viewer's relationship and apply owner privacy rules. Current-user and write endpoints
require `Authorization: Bearer {accessToken}`. See `docs/auth-api.md` and
`docs/profile-social-api.md` for the Android integration contract.

See `docs/social-feed-notification-api.md` for the Android contract and the
Kafka/Outbox delivery model.

See `docs/chat-api.md` for persistent direct/group chat, message idempotency,
history/read cursors, membership authorization, the chat Outbox event, and the
realtime WebSocket delivery contract.

## Social event delivery

Business writes and their Outbox rows commit in the same PostgreSQL transaction.
With Kafka enabled, the Outbox publisher sends versioned envelopes to:

```text
flower-show.domain-events.v1
flower-show.feed-fanout.v1
flower-show.domain-events.v1.DLT
flower-show.feed-fanout.v1.DLT
```

Ordinary authors use fanout-on-write in bounded chunks. Authors above
`FANOUT_ON_WRITE_MAX_FOLLOWERS` use fanout-on-read; the following feed merges both
sources with cursor pagination. Users only receive `new_post` notifications for
relationships where `notify_new_content=true`, and muted relationships appear in
neither feed nor new-content notifications.

Kafka delivery is at least once. Deterministic notification IDs, feed primary keys,
dedupe keys, and `consumer_processed_events` make replays idempotent. The Compose
broker is a persistent single-node KRaft setup for development. Production should
run at least three brokers, replication factor 3, `min.insync.replicas=2`, and keep
producer `acks=all`.
