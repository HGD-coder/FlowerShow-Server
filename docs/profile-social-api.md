# Profile and follow API

Base path: `/api/v1`

Send the access token for current-user and write requests:

```http
Authorization: Bearer {accessToken}
```

The token is optional for public profile reads. When supplied, relationship and
owner-only fields are calculated from the JWT subject.

The current user's own page can use the shorter routes below. All require a Bearer JWT:

```http
GET /me
PATCH /me
GET /me/contents?tab=posts|liked|favorites&page=1&pageSize=20
```

They return the same DTOs as the user-scoped profile routes.

## Profile

```http
GET /users/{userId}/profile
Authorization: Bearer {accessToken}
```

Important response fields:

- `ownProfile`: whether the viewer owns this profile.
- `profileContentVisible`: whether the viewer may load profile content.
- `following`, `followedBy`, `mutualFollow`: relationship relative to the JWT subject.
- `postCount`, `receivedLikeCount`, `followingCount`, `followerCount`: profile counters.
- `likedTabVisible`, `favoritesTabVisible`: whether the app should render and load each tab.
- `likedContentCount`, `favoriteContentCount`: omitted when that tab is private.

The profile owner can update fields and privacy settings:

```http
PATCH /users/{userId}/profile
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "handle": "balcony.lin",
  "nickname": "Balcony Lin",
  "avatarUrl": "https://example.com/avatar.jpg",
  "profileBannerUrl": "https://example.com/banner.jpg",
  "bio": "Small-space gardening",
  "location": "Hangzhou",
  "profileVisibility": "public",
  "showLikedOnProfile": false,
  "showFavoritesOnProfile": false
}
```

Every field is optional. Sending an empty optional text field clears it.
`profileVisibility` supports `public` and `private`.

## Profile content

```http
GET /users/{userId}/profile/contents?tab=posts&page=1&pageSize=20
GET /users/{userId}/profile/contents?tab=liked&page=1&pageSize=20
GET /users/{userId}/profile/contents?tab=favorites&page=1&pageSize=20
Authorization: Bearer {accessToken}
```

All three endpoints use the same response envelope:

```json
{
  "items": [],
  "page": 1,
  "pageSize": 20,
  "total": 0,
  "hasMore": false
}
```

The owner sees their own published posts, likes, and favorites. Visitors see only
public posts marked for profile display. Liked and favorite tabs return `403` unless
the owner enabled the matching profile setting. A private profile's content also
returns `403` for visitors.

The owner can hide, pin, or order one of their posts:

```http
PATCH /users/{userId}/contents/{contentId}/profile-display
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "showOnProfile": true,
  "pinned": true,
  "sortOrder": 10
}
```

At least one field is required. `sortOrder` must be zero or greater.

## Followers and following

```http
GET /users/{userId}/followers?keyword=lin&page=1&pageSize=20
GET /users/{userId}/following?keyword=flower&page=1&pageSize=20
Authorization: Bearer {accessToken}
```

Search matches nickname or handle, case-insensitively. Each item includes profile
summary fields, follower/following counters, and `following`, `followedBy`, and
`mutualFollow` relative to the current viewer.

Follow and unfollow use the existing routes:

```http
POST /users/{actorUserId}/following/{targetUserId}
DELETE /users/{actorUserId}/following/{targetUserId}
Authorization: Bearer {accessToken}
```

The response includes `changed`, current relationship state, the target's
`followerCount`, and the actor's `followingCount`. Repeating the same operation is
idempotent and returns `changed: false`.

The current user can configure each followed author independently:

```http
GET /users/{actorUserId}/following/{targetUserId}/preferences
PATCH /users/{actorUserId}/following/{targetUserId}/preferences
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "notifyNewContent": true,
  "muted": false
}
```

Both patch fields are optional, but at least one is required. `notifyNewContent`
controls new-video notifications. `muted` removes the author's content from the
following feed and suppresses new-video notifications; unmuting backfills the
author's most recent public content.

For every write, `{actorUserId}` must equal the JWT subject. `X-User-Id` is no longer
accepted as authentication.
