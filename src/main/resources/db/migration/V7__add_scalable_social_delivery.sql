alter table users
    add column fanout_mode varchar(20) not null default 'auto';

alter table follows
    add column notify_new_content boolean not null default false;

alter table follows
    add column muted boolean not null default false;

alter table follows
    add column fanout_shard int not null default 0;

alter table follows
    add column updated_at timestamp not null default current_timestamp;

create table user_social_stats (
    user_id varchar(64) primary key,
    follower_count bigint not null default 0,
    following_count bigint not null default 0,
    updated_at timestamp not null default current_timestamp,
    constraint fk_user_social_stats_user foreign key (user_id) references users(id) on delete cascade,
    constraint ck_user_social_stats_follower_count check (follower_count >= 0),
    constraint ck_user_social_stats_following_count check (following_count >= 0)
);

insert into user_social_stats (user_id, follower_count, following_count)
select u.id,
       (select count(*) from follows follower_relation where follower_relation.following_id = u.id),
       (select count(*) from follows following_relation where following_relation.follower_id = u.id)
from users u;

create table user_feed_entries (
    user_id varchar(64) not null,
    content_id varchar(64) not null,
    author_user_id varchar(64) not null,
    source_event_id varchar(64),
    score bigint not null,
    created_at timestamp not null default current_timestamp,
    primary key (user_id, content_id),
    constraint fk_user_feed_user foreign key (user_id) references users(id) on delete cascade,
    constraint fk_user_feed_content foreign key (content_id) references content_items(id) on delete cascade,
    constraint fk_user_feed_author foreign key (author_user_id) references users(id) on delete cascade
);

insert into user_feed_entries (user_id, content_id, author_user_id, source_event_id, score)
select f.follower_id, c.id, c.author_user_id, null, coalesce(c.publish_time, 0)
from follows f
join content_items c on c.author_user_id = f.following_id
where c.status = 'published'
  and c.visibility = 'public';

alter table notifications
    add column event_id varchar(64);

alter table notifications
    add column dedupe_key varchar(180);

alter table notifications
    add column payload_json text;

alter table notifications
    add column updated_at timestamp not null default current_timestamp;

create table notification_unread_stats (
    user_id varchar(64) primary key,
    unread_count bigint not null default 0,
    updated_at timestamp not null default current_timestamp,
    constraint fk_notification_unread_user foreign key (user_id) references users(id) on delete cascade,
    constraint ck_notification_unread_count check (unread_count >= 0)
);

insert into notification_unread_stats (user_id, unread_count)
select u.id,
       (select count(*) from notifications n where n.receiver_user_id = u.id and n.read_at is null)
from users u;

create table consumer_processed_events (
    consumer_name varchar(100) not null,
    event_id varchar(100) not null,
    processed_at timestamp not null default current_timestamp,
    primary key (consumer_name, event_id)
);

alter table outbox_events
    add column partition_key varchar(100);

alter table outbox_events
    add column schema_version int not null default 1;

alter table outbox_events
    add column occurred_at timestamp not null default current_timestamp;

alter table outbox_events
    add column next_attempt_at timestamp not null default current_timestamp;

alter table outbox_events
    add column claimed_at timestamp;

alter table outbox_events
    add column claimed_by varchar(100);

update outbox_events
set partition_key = aggregate_id
where partition_key is null;

create table user_device_tokens (
    id varchar(64) primary key,
    user_id varchar(64) not null,
    token varchar(512) not null,
    platform varchar(20) not null,
    device_name varchar(100),
    enabled boolean not null default true,
    created_at timestamp not null default current_timestamp,
    last_seen_at timestamp not null default current_timestamp,
    constraint uq_user_device_token unique (token),
    constraint fk_user_device_token_user foreign key (user_id) references users(id) on delete cascade
);

create table notification_deliveries (
    id varchar(64) primary key,
    notification_id varchar(64) not null,
    device_token_id varchar(64) not null,
    status varchar(20) not null default 'pending',
    attempt_count int not null default 0,
    next_attempt_at timestamp not null default current_timestamp,
    delivered_at timestamp,
    last_error text,
    created_at timestamp not null default current_timestamp,
    constraint uq_notification_device_delivery unique (notification_id, device_token_id),
    constraint fk_notification_delivery_notification foreign key (notification_id) references notifications(id) on delete cascade,
    constraint fk_notification_delivery_device foreign key (device_token_id) references user_device_tokens(id) on delete cascade
);

create index idx_follows_fanout_scan
    on follows(following_id, muted, follower_id);

create index idx_follows_new_content_notification
    on follows(following_id, notify_new_content, follower_id);

create index idx_user_feed_order
    on user_feed_entries(user_id, score desc, content_id desc);

create index idx_user_feed_author
    on user_feed_entries(user_id, author_user_id);

create unique index idx_notifications_receiver_dedupe
    on notifications(receiver_user_id, dedupe_key);

create index idx_notifications_receiver_unread
    on notifications(receiver_user_id, read_at, created_at desc, id desc);

create index idx_outbox_ready
    on outbox_events(status, next_attempt_at, created_at);

create index idx_device_tokens_user_enabled
    on user_device_tokens(user_id, enabled);

create index idx_notification_deliveries_ready
    on notification_deliveries(status, next_attempt_at, created_at);
