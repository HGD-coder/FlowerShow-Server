# Authentication API

Base path: `/api/v1/auth`

## Register

```http
POST /register
Content-Type: application/json

{
  "username": "garden.user",
  "password": "Garden@123",
  "nickname": "Garden User",
  "avatarUrl": "https://example.com/avatar.jpg",
  "bio": "Balcony gardening"
}
```

Registration returns `201 Created` and signs the user in immediately. Usernames are
stored lowercase, must be 3-32 characters, and support letters, numbers, dot,
underscore, and hyphen. Passwords must contain 8-72 characters and no more than 72
UTF-8 bytes.

## Login

```http
POST /login
Content-Type: application/json

{
  "username": "garden.user",
  "password": "Garden@123"
}
```

Register and login return the same token response:

```json
{
  "tokenType": "Bearer",
  "accessToken": "eyJ...",
  "accessTokenExpiresInSeconds": 900,
  "refreshToken": "opaque-random-token",
  "refreshTokenExpiresInSeconds": 2592000,
  "user": {
    "userId": "usr_...",
    "accountId": "acc_...",
    "username": "garden.user",
    "nickname": "Garden User",
    "role": "user"
  }
}
```

Send the access token on protected requests:

```http
Authorization: Bearer {accessToken}
```

Five consecutive password failures lock the account for 15 minutes. A successful
login resets the failure counter.

## Refresh

```http
POST /refresh
Content-Type: application/json

{
  "refreshToken": "current-refresh-token"
}
```

Refresh tokens are single use. Every successful refresh returns a new access token and
a new refresh token; the app must replace both values atomically. Reusing the old token
returns `401`. Only a SHA-256 digest of each refresh token is stored in PostgreSQL.

## Current account

```http
GET /me
Authorization: Bearer {accessToken}
```

This returns the authenticated account summary. `/api/v1/me` returns the full profile.

## Logout

```http
POST /logout
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "refreshToken": "current-refresh-token"
}
```

`POST /logout-all` revokes every active refresh token for the authenticated account.
An already-issued access token remains valid until its short expiration time.

## Android token flow

Keep the access token in memory and place the refresh token in platform-backed
encrypted storage. On one `401`, perform a single synchronized refresh, replace both
tokens, and retry the original request once. If refresh also returns `401`, clear the
local session and show the login screen.
