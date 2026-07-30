alter table user_device_tokens
    add column push_provider varchar(20) not null default 'fcm';

alter table user_device_tokens
    add column device_brand varchar(100);

alter table user_device_tokens
    add column app_package varchar(255);

update user_device_tokens
set push_provider = 'fcm';

alter table user_device_tokens
    drop constraint uq_user_device_token;

alter table user_device_tokens
    add constraint uq_user_device_provider_token
        unique (push_provider, token);

alter table user_device_tokens
    add constraint ck_user_device_push_provider
        check (push_provider in ('fcm', 'huawei', 'xiaomi', 'oppo', 'vivo'));

alter table user_device_tokens
    add constraint ck_user_device_provider_recipient_type
        check (push_provider = 'fcm' or recipient_type = 'token');
