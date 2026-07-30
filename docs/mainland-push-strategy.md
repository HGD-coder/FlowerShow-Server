# Mainland China multi-channel push strategy

## Scope and current status

This document defines the staged notification channel strategy for mainland
China without expanding into chat or implementing WebSocket in phase 1.

The durable source of truth is PostgreSQL:

- `notifications` stores the in-app notification history.
- `notification_deliveries` stores per-device remote delivery state.
- Remote channels are delivery mechanisms, not an alternate notification store.
- `PUSH_ENABLED=false` stops the remote Worker only. Notification creation and
  delivery-row creation continue unchanged.

Phase 1 implements the provider-neutral registration, persistence, claim,
message, routing, and error contracts. It includes one real adapter: FCM.
Huawei, Xiaomi, OPPO, and vivo adapters are not implemented yet.

## Target channel split

| Device situation | Target channel | Status |
| --- | --- | --- |
| App online | WebSocket backed by the PostgreSQL fact source | Near-term priority; not implemented |
| Offline in mainland China | Huawei/Xiaomi/OPPO/vivo adapter or an approved aggregation channel | Contract only; adapters pending |
| GMS-capable or overseas device | FCM | Implemented |
| In-app history/read state | PostgreSQL API | Implemented and authoritative |

An aggregation provider may replace several vendor-specific integrations later,
but selecting one requires a separate product, privacy, reliability, and
commercial review. Phase 1 does not assume or impersonate an aggregation
provider.

## Phase 1 contract

`POST /api/v1/me/devices` accepts:

- `pushProvider`: optional, defaults to `fcm`; one of `fcm`, `huawei`,
  `xiaomi`, `oppo`, or `vivo`.
- `recipientType`: optional, defaults to `token`; FCM accepts `token` or `fid`,
  while mainland providers accept only `token`.
- `deviceBrand`: optional, at most 100 characters.
- `appPackage`: optional, at most 255 characters.

Request text is trimmed and enum-like values are normalized to lowercase.
Responses and lists return provider/type/brand/package metadata but never return
the token or FID.

Device ownership transfer and deduplication use `(push_provider, token)`.
Deterministic IDs for newly inserted devices include the provider, so equal token
text from different providers does not collide. Existing FCM rows keep their
current IDs.

Each Worker claim and message carries its provider. The router selects an
adapter per row:

- FCM routes to the real `FcmPushSender` adapter.
- A missing adapter returns permanent `PROVIDER_NOT_CONFIGURED`.
- The delivery becomes `dead` with an actual-provider prefix such as
  `HUAWEI_PROVIDER_NOT_CONFIGURED`.
- A missing adapter does not disable the device.
- Existing invalid-recipient results from a configured adapter still disable the
  device and terminate its remaining queue.

`PUSH_PROVIDER` is no longer a global route selector. The legacy environment
variable/property can remain set and is ignored. `PUSH_ENABLED` remains the
global remote-Worker switch. FCM credentials remain lazily loaded at the first
real FCM send, never at application startup.

## Mainland adapter prerequisites

Before adding any mainland adapter, obtain and validate:

1. Vendor or aggregation-provider accounts and app approval.
2. App identifiers, package/signature bindings, credentials, rotation process,
   and secret storage.
3. Provider-specific payload, TTL, collapse, quota, receipt, and error semantics.
4. A mapping from provider errors to retryable, permanent, and invalid-recipient
   outcomes.
5. Sandbox or approved production test devices for delivery and revocation tests.
6. Privacy, data residency, logging, and operational monitoring approval.

No adapter should fabricate credentials or make a vendor network call until
these inputs are available.

## Deployment and rollback notes

Flyway V13 sets existing device rows to `fcm`, adds provider metadata, and
changes token uniqueness from global `token` to `(push_provider, token)`. It also
enforces the provider list and rejects `fid` for non-FCM rows.

The migration is forward-only. After different providers store identical token
text, a rollback to the old global unique-token constraint requires explicit
data reconciliation first. Back up PostgreSQL and validate V13 in a staging
database before production rollout.

Enabling `PUSH_ENABLED` before mainland adapters exist intentionally marks their
queued deliveries `dead` as not configured. Keep it disabled if operators want
those rows to remain queued until the required adapters and credentials are
ready.
