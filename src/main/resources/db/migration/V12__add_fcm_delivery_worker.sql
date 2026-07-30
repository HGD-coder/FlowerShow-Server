alter table user_device_tokens
    add column recipient_type varchar(10) not null default 'token';

alter table notification_deliveries
    add column claimed_at timestamp;

alter table notification_deliveries
    add column claimed_by varchar(100);

alter table notification_deliveries
    add column provider_message_id varchar(512);

update notification_deliveries
set status = 'retry',
    next_attempt_at = current_timestamp
where status = 'processing';

update notification_deliveries
set status = 'dead',
    last_error = 'MIGRATED_UNSUPPORTED_STATUS'
where status not in ('pending', 'retry', 'processing', 'delivered', 'dead');

alter table user_device_tokens
    add constraint ck_user_device_recipient_type
        check (recipient_type in ('token', 'fid'));

alter table notification_deliveries
    add constraint ck_notification_delivery_status
        check (status in ('pending', 'retry', 'processing', 'delivered', 'dead'));

alter table notification_deliveries
    add constraint ck_notification_delivery_attempt_count
        check (attempt_count >= 0);

alter table notification_deliveries
    add constraint ck_notification_delivery_claim
        check (
            (status = 'processing' and claimed_at is not null and claimed_by is not null)
            or
            (status <> 'processing' and claimed_at is null and claimed_by is null)
        );

create index idx_notification_deliveries_claim_recovery
    on notification_deliveries(status, claimed_at);
