# Persistent chat API

All chat endpoints are under `/api/v1/chat` and require:

```http
Authorization: Bearer {accessToken}
Content-Type: application/json
```

The JWT subject is the acting user. Request bodies never accept an alternative
sender or owner ID.

## Core rules

- A direct conversation can only be created while both users follow each other.
  The same unordered user pair always resolves to one conversation.
- Sending to a direct conversation rechecks the mutual-follow relationship.
- A group creator becomes its owner. The owner may initially invite or later add
  only existing users who mutually follow the owner.
- A group has at most 200 active members including its owner. Capacity checks
  are serialized on the conversation row so separate add requests cannot exceed
  the limit.
- Only an active member can list, inspect, read, send to, or mark a conversation
  read. Only the group owner can rename the group or add/remove members.
- The active group owner can transfer ownership to another active member. The
  role swap, conversation owner update, timestamp, and Outbox event commit in
  one transaction.
- A non-owner member may leave. An owner receives `409 Conflict` until ownership
  is transferred or the group is dissolved.
- Dissolution is soft and permanent. It preserves membership and message rows.
  Active members can still list the conversation, inspect details and history,
  and mark messages read, but sending and all group mutations return
  `409 Conflict`.
- Historical messages are returned newest first. Newly added or re-added group
  members start with the current message as their unread watermark, while active
  members may read the conversation's complete retained history.

## Conversations

### List conversations

```http
GET /api/v1/chat/conversations?pageSize=20&cursor={opaqueCursor}
```

`pageSize` is 1–50. The response is ordered by latest message time, falling back
to conversation creation time:

```json
{
  "items": [
    {
      "id": "con_...",
      "type": "direct",
      "displayName": "Chat Bob",
      "displayAvatarUrl": "https://cdn.example/avatar.jpg",
      "avatarMembers": [],
      "state": "active",
      "dissolvedAt": null,
      "lastMessage": {
        "id": "msg_...",
        "conversationId": "con_...",
        "senderUserId": "user-a",
        "senderNickname": "Alice",
        "senderAvatarUrl": "https://cdn.example/alice.jpg",
        "type": "text",
        "text": "hello",
        "sharedContent": null,
        "clientMessageId": "android-local-42",
        "createdAt": "2026-07-24T09:00:00Z"
      },
      "unreadCount": 1,
      "updatedAt": "2026-07-24T09:00:00Z"
    }
  ],
  "nextCursor": "opaque",
  "hasMore": true
}
```

For groups, `name`, `displayName`, and `ownerUserId` are present.
`avatarMembers` contains at most the first four active members. The current owner
is first, followed by members ordered by `joined_at` and then `user_id`. Members
who left or were removed are excluded. Direct conversations return
`avatarMembers: []` and continue to use `displayAvatarUrl`.

### Create or resolve a direct conversation

```http
POST /api/v1/chat/conversations/direct
```

```json
{
  "userId": "other-user-id"
}
```

The endpoint returns `200 OK` with the existing conversation when the pair
already has one.

### Create a group

```http
POST /api/v1/chat/conversations/group
```

```json
{
  "name": "Balcony gardeners",
  "memberUserIds": ["user-b", "user-c"]
}
```

`name` is trimmed, required, and limited to 120 characters.
`memberUserIds` may be omitted or empty and can contain at most 199 entries,
leaving one active-member slot for the owner.

### Get conversation details and active members

```http
GET /api/v1/chat/conversations/{conversationId}
```

```json
{
  "id": "con_...",
  "type": "group",
  "name": "Balcony gardeners",
  "ownerUserId": "user-a",
  "state": "active",
  "dissolvedAt": null,
  "dissolvedByUserId": null,
  "members": [
    {
      "userId": "user-a",
      "nickname": "Alice",
      "avatarUrl": "https://cdn.example/alice.jpg",
      "role": "owner",
      "joinedAt": "2026-07-24T09:00:00Z"
    }
  ],
  "createdAt": "2026-07-24T09:00:00Z",
  "updatedAt": "2026-07-24T09:00:00Z"
}
```

### Rename a group

```http
PATCH /api/v1/chat/conversations/{conversationId}
```

```json
{
  "name": "New group name"
}
```

Owner only. The response is the updated conversation detail.

### Add group members

```http
POST /api/v1/chat/conversations/{conversationId}/members
```

```json
{
  "userIds": ["user-d", "user-e"]
}
```

`userIds` must contain 1–200 entries. Already-active members do not consume
additional capacity. Missing members and previously removed or departed members
each consume one slot when added or reactivated, and reactivation still requires
mutual follow with the owner. If the resulting active membership would exceed
200, the whole request returns `409 Conflict` without partial membership writes.

### Remove a group member

```http
DELETE /api/v1/chat/conversations/{conversationId}/members/{userId}
```

Owner only. The owner cannot remove themself. The response is the updated
conversation detail.

### Transfer group ownership

```http
POST /api/v1/chat/conversations/{conversationId}/owner
```

```json
{
  "userId": "user-b"
}
```

Only the current active owner of an active group can transfer ownership. The
target must be an active member. Transferring to the current owner or applying
this operation to a direct conversation returns `409 Conflict`. A successful
response is the updated conversation detail: the previous owner has
`role=member`, the target has `role=owner`, and `ownerUserId` identifies the
target immediately for subsequent rename, membership, transfer, and dissolution
authorization.

### Dissolve a group

```http
POST /api/v1/chat/conversations/{conversationId}/dissolve
```

Only the current active owner can dissolve an active group. The response is the
current detail with:

```json
{
  "state": "dissolved",
  "dissolvedAt": "2026-07-24T09:10:00Z",
  "dissolvedByUserId": "user-a"
}
```

Retrying this endpoint as the same current owner returns the existing detail and
does not write a second event. A non-owner receives `403 Forbidden`; a direct
conversation receives `409 Conflict`.

### Leave a group

```http
POST /api/v1/chat/conversations/{conversationId}/leave
```

```json
{
  "changed": true
}
```

Direct conversations cannot be left in this version.

All group mutation endpoints in this section, including leave and owner
transfer, return `409 Conflict` after dissolution.

## Messages

### List message history

```http
GET /api/v1/chat/conversations/{conversationId}/messages?pageSize=50&cursor={opaqueCursor}
```

`pageSize` is 1–100. Messages are ordered newest first. Pass `nextCursor`
unchanged to request the next older page.

The send response, each history item, and a conversation summary's
`lastMessage` use the same message shape. Sender fields come from the sender's
current `users` profile, including for retained messages from members who later
left or were removed. They do not depend on active conversation membership.

For `video_share`, the server includes the compact preview needed to render the
message without another content lookup:

```json
{
  "id": "msg_...",
  "conversationId": "con_...",
  "senderUserId": "user-a",
  "senderNickname": "Alice",
  "senderAvatarUrl": "https://cdn.example/alice.jpg",
  "type": "video_share",
  "sharedContentId": "video-content-id",
  "sharedContent": {
    "id": "video-content-id",
    "title": "Balcony garden refresh",
    "coverUrl": "https://cdn.example/video-cover.jpg",
    "authorUserId": "author-user-id",
    "authorNickname": "Balcony Lin"
  },
  "clientMessageId": "bbda87cb-f8e3-4d54-869e-e6258fbc3545",
  "createdAt": "2026-07-24T09:00:00Z"
}
```

`sharedContent` is non-null only for `video_share`. Text messages always return
`sharedContent: null`; the existing `sharedContentId` field remains on video
shares for compatibility.

### Send text

```http
POST /api/v1/chat/conversations/{conversationId}/messages
```

```json
{
  "type": "text",
  "text": "See you at the flower market",
  "clientMessageId": "98fef93e-1a92-4fa2-b633-b6eeaf82f90c"
}
```

`type` is limited to 20 Java characters. Text must be non-blank, cannot include
`contentId`, and is limited to 4000 Java `String.length()` characters (UTF-16
code units).

### Share a video

```json
{
  "type": "video_share",
  "contentId": "video-content-id",
  "clientMessageId": "bbda87cb-f8e3-4d54-869e-e6258fbc3545"
}
```

The referenced content must exist at send time with `type=video`,
`status=published`, and `visibility=public`. A video share cannot include text.

### Idempotency

`clientMessageId` is required, limited to 128 characters, and scoped to the
conversation plus authenticated sender. Retrying the same ID with the same
normalized payload returns the original message and does not create another
Outbox event. Reusing it with another type, text, or content ID returns
`409 Conflict`.

The uniqueness constraint is:

```text
(conversation_id, sender_user_id, client_message_id)
```

### Mark read

Mark through one message:

```http
POST /api/v1/chat/conversations/{conversationId}/read
```

```json
{
  "messageId": "msg_..."
}
```

Omit the body to mark through the current last message. Read watermarks only
move forward.

```json
{
  "conversationId": "con_...",
  "lastReadMessageId": "msg_...",
  "readAt": "2026-07-24T09:05:00Z",
  "unreadCount": 0
}
```

Unread counts exclude messages sent by the current user.

## Error status contract

- `400 Bad Request`: malformed fields, unsupported message shape, invalid cursor,
  or an invalid/non-public video share.
- `401 Unauthorized`: missing or invalid access token.
- `403 Forbidden`: mutual-follow requirement not met, inactive/non-member access,
  or a non-owner group management attempt.
- `404 Not Found`: referenced user, conversation, or read-watermark message does
  not exist.
- `409 Conflict`: owner/direct leave, removing the owner, applying a group-only
  operation to a direct conversation, exceeding group capacity during member
  addition, transferring ownership to self, mutating or sending to a dissolved
  group, or idempotency-key payload mismatch.
- `413 Payload Too Large`: the Nginx gateway rejects chat request bodies larger
  than 64 KiB before proxying them to Spring.

## Persistence and events

Flyway `V10__add_persistent_chat.sql` creates:

- `chat_conversations`;
- `chat_conversation_members`;
- `chat_messages`.

Messages use a database identity sequence for stable history cursors and read
watermarks. Direct user columns have an unordered-pair uniqueness constraint,
and all relationships have foreign keys plus shape checks.

Flyway `V11__add_chat_group_lifecycle.sql` adds `conversation_state` with the
backward-compatible default `active`, plus `dissolved_at` and
`dissolved_by_user_id`. Its foreign-key and check constraints enforce:

- direct conversations are always active and have no dissolution metadata;
- active groups have no dissolution metadata;
- dissolved groups have both a timestamp and dissolving user.

Every message insert, owner transfer, or first dissolution and its Outbox row
commit in one transaction. All chat event envelopes use
`aggregate_type=chat_conversation`, the conversation ID for both `aggregate_id`
and `partition_key`, and `schema_version=1`.

Message events use:

```text
event_type     = CHAT_MESSAGE_CREATED
```

The payload contains `eventVersion=1`, `conversationId`, `messageId`,
`senderUserId`, `messageType`, optional `sharedContentId`, and `createdAt`.
It also contains `message`, whose complete shape is identical to the REST
`ChatMessageDto`. Existing top-level fields remain for consumer compatibility.

Owner-transfer events use:

```text
event_type = CHAT_GROUP_OWNER_TRANSFERRED
```

The payload contains `eventVersion=1`, `conversationId`,
`previousOwnerUserId`, `newOwnerUserId`, and `transferredAt`.

The first successful dissolution uses:

```text
event_type = CHAT_GROUP_DISSOLVED
```

The payload contains `eventVersion=1`, `conversationId`,
`dissolvedByUserId`, and `dissolvedAt`. An idempotent owner retry does not emit a
second event.

## Realtime WebSocket delivery

Message writes remain on the REST send endpoint. PostgreSQL messages and the
transactional `CHAT_MESSAGE_CREATED` Outbox event remain the only durable truth.
The Spring-managed Netty listener only delivers online events and accepts small
ACK/control frames.

Connect to:

```http
GET /ws/chat
Connection: Upgrade
Upgrade: websocket
Authorization: Bearer {accessToken}
```

Use external `wss://{public-host}/ws/chat` through the TLS gateway. The default
internal listener is `ws://localhost:8090/ws/chat`; Nginx proxies to
`ws://host.docker.internal:8090/ws/chat`.

Missing or invalid JWTs receive HTTP `401` and the connection closes. The same
JWT decoder as REST validates signature, issuer, audience, and expiry; JWT `sub`
is the connected user ID. One user may keep multiple channels.

After Upgrade the server sends:

```json
{"type":"connection.ready"}
```

For each `CHAT_MESSAGE_CREATED`, the event processor queries membership state at
processing time. Every online channel of each still-`active` member, including
the sender's other devices, receives:

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

Clients deduplicate by `message.id` and recover missed messages through REST
history. They may acknowledge a delivery with:

```json
{"type":"ack","eventId":"evt_..."}
```

ACKs are validated but not persisted. Business message commands, binary frames,
fragmented text, malformed JSON, and unknown text control types are rejected.
The server handles Ping/Pong, closes idle connections, and limits frame payload
size.
