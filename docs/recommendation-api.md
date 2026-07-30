# Organic recommendation API

The recommendation API is part of the existing Flower Show Spring Boot service.
It has no advertising inventory or advertising delivery branch. All delivered
cards use:

- `deliveryType: "organic"`
- `policyVersion: "organic-only-v1"`

Only rows in `content_items` with `status = 'published'`,
`visibility = 'public'`, and `type = 'video'` are eligible.

## Identity

A request with a verified access JWT uses the JWT `sub`. Otherwise callers must
send a non-blank `X-Install-Id` header of at most 256 characters. JWT identity
takes precedence when both are supplied.

The recommendation tables store only a SHA-256 actor key. They never store the
raw install ID, bearer token, or JWT subject.

## Envelope

The four endpoints return the same envelope. Both nullable fields are always
present on the wire.

```json
{
  "requestId": "uuid",
  "traceId": "hex-id",
  "serverTimeMs": 1780000000000,
  "data": {},
  "error": null
}
```

Errors set `data` to `null` and return:

```json
{
  "requestId": "uuid",
  "traceId": "hex-id",
  "serverTimeMs": 1780000000000,
  "data": null,
  "error": {
    "code": "INVALID_LIMIT",
    "message": "limit must be between 1 and 50.",
    "details": null
  }
}
```

## Feed pages

`POST /api/v1/feed/pages`

```json
{
  "clientRequestId": "feed-1",
  "serveSessionId": null,
  "cursor": null,
  "limit": 20,
  "scene": "home",
  "refresh": false
}
```

The response `data` contains `serveSessionId`, `items`, `nextCursor`,
`hasMore`, `serveMode`, `algoVersion`, `policyVersion`, and `expiresAtMs`.
Each item contains the server rank, organic delivery metadata, the existing
camelCase video card payload, a signed exposure token, and its own
`policyVersion: "organic-only-v1"`.

A refresh creates a new immutable ordering snapshot. Paging uses the cursor's
snapshot and absolute offset; it does not rerank between pages. Feed scoring
combines database engagement signals, freshness, actor interests, deterministic
exploration, and an author repetition penalty. When a vector profile exists for
the actor, cosine similarity between that profile and content embeddings adds a
bounded semantic-affinity signal. A missing profile leaves the original formula
unchanged.

## Search guesses

`POST /api/v1/search/guesses`

```json
{
  "clientRequestId": "guess-1",
  "serveSessionId": null,
  "cursor": null,
  "limit": 20,
  "refresh": false
}
```

Suggestions are generated only from `content_recommend_words` and
`content_tags` attached to eligible videos. Global engagement/video-count
signals are combined with the actor's persisted interests. When available, the
actor's vector profile also ranks semantically related suggestions even if the
stored interest and suggestion are not exact text matches. No Android asset is
read by the server.

## Video search

`POST /api/v1/search/videos`

```json
{
  "clientRequestId": "search-1",
  "query": "rose care",
  "cursor": null,
  "limit": 20
}
```

Search uses a hybrid retrieval strategy:

1. Lexical relevance checks title, author, tags, and recommendation words.
2. Ollama embeds the normalized query and PostgreSQL pgvector retrieves cosine
   similarity candidates.
3. Both candidate sets are merged, while exact lexical matches retain the
   largest score weight.
4. Actor interest, popularity, and deterministic jitter are small tie-breakers.

Semantic-only results are accepted only at or above
`EMBEDDING_MINIMUM_SIMILARITY`. Item `source` is `lexical_search`,
`semantic_search`, or `hybrid_search` when vector scoring is active. If vector
scoring is disabled or unavailable, the previous lexical path and
`server_search` source remain active. Search cursors contain a hash of the
normalized query and cannot be reused with different search text.

## Semantic index

The optional semantic layer uses local Ollama and PostgreSQL pgvector. It does
not replace PostgreSQL or the existing ranking rules.

- `content_embeddings` stores one vector for each eligible public video, built
  from title, description, tags, and recommendation words.
- `suggestion_embeddings` stores vectors for the current deduplicated tag and
  recommendation-word candidates.
- `user_interest_embeddings` stores a vector profile built from the actor's
  weighted `recommendation_user_interests`.
- Source hashes make refreshes incremental. Successfully completed refreshes
  also remove vectors whose source content, suggestion, or user profile no
  longer exists.
- The background worker uses its own single-thread scheduler. The default first
  refresh starts after 10 seconds and later refreshes run every 10 minutes.
- Search-query embedding happens synchronously, but outside the recommendation
  database transaction. Provider or vector-query failures are caught so
  database-only ranking can continue.

Current local data volume is small, so pgvector exact cosine scans are used.
Before moving to a large catalog, pin a fixed vector dimension in the schema and
add an HNSW index after measuring recall and latency.

Start the complete local stack with:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-flowershow.ps1 `
  -EnableSemanticSearch
```

Or start only the semantic dependencies:

```powershell
docker compose --profile semantic up -d ollama
docker compose --profile semantic run --rm --no-deps ollama-model
```

Configuration:

| Environment variable | Default | Meaning |
| --- | --- | --- |
| `EMBEDDING_ENABLED` | `false` | Enables indexing and semantic scoring. |
| `EMBEDDING_BASE_URL` | `http://localhost:11434` | Ollama API base URL. |
| `EMBEDDING_MODEL` | `qwen3-embedding:0.6b` | Embedding model and index model key. |
| `EMBEDDING_DIMENSIONS` | `1024` | Expected and requested vector dimensions. |
| `EMBEDDING_BATCH_SIZE` | `16` | Documents sent in each indexing request. |
| `EMBEDDING_CANDIDATE_LIMIT` | `200` | Maximum vector candidates per ranking request. |
| `EMBEDDING_MINIMUM_SIMILARITY` | `0.35` | Minimum cosine similarity accepted by ranking. |
| `EMBEDDING_CONNECT_TIMEOUT` | `2s` | Ollama connection timeout. |
| `EMBEDDING_REQUEST_TIMEOUT` | `30s` | Ollama request timeout. |
| `EMBEDDING_INITIAL_DELAY` | `10s` | Delay before the first index refresh. |
| `EMBEDDING_REFRESH_INTERVAL` | `10m` | Delay between completed index refreshes. |

## Events

`POST /api/v1/events:batch`

```json
{
  "events": [
    {
      "eventId": "event-1",
      "type": "impression",
      "occurredAtMs": 1780000000000,
      "exposureToken": "signed-token",
      "contentId": "v001",
      "playbackId": "playback-1",
      "sequence": 1
    }
  ]
}
```

Supported types are `impression`, `play_start`, `suggestion_click`,
`search_submit`, `watch`, `like`, `favorite`, and `share`. All content and
suggestion events require a signed exposure token. `search_submit` may omit the
token because it can occur before a result or suggestion exposure, but requires
a query.

The server verifies token signature, actor, session snapshot, entity, rank,
kind, and expiry. `eventId` is idempotent per actor. Accepted events update
`recommendation_user_interests` and publish `RECOMMENDATION_BEHAVIOR` through
the existing transactional outbox. Raw authorization values, signed tokens,
and search text are not written to application logs or outbox payloads.

## Limits and idempotency

- JSON request body: at most 64 KiB at the Nginx gateway, including chunked requests;
  Spring also rejects an oversized declared `Content-Length`
- `clientRequestId` and `eventId`: 1–128 safe characters
- page `limit`: 1–50, default 20
- query: 1–200 characters
- cursor/exposure token: at most 4096 characters
- event batch: 1–100 entries
- `serveSessionId`: at most 128 characters
- scene: at most 64 characters

`clientRequestId` is scoped by actor and endpoint. An identical retry replays
the exact stored envelope. Reuse with a different request body returns HTTP
409 `IDEMPOTENCY_KEY_REUSED`. The default idempotency lifetime is 24 hours and
the default serve-session lifetime is 30 minutes.

## Compatibility

The existing `GET /api/v1/feed`, `GET /api/v1/search`, video endpoints, and
following feeds are unchanged. Their legacy response shapes are not handled by
the recommendation-specific exception advice.
