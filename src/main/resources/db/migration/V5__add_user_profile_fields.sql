alter table users
    add column handle varchar(64);

alter table users
    add column profile_banner_url text;

alter table users
    add column profile_visibility varchar(20) not null default 'public';

alter table users
    add column show_liked_on_profile boolean not null default false;

alter table users
    add column show_favorites_on_profile boolean not null default false;

alter table users
    add column updated_at timestamp not null default current_timestamp;

update users
set handle = id
where handle is null;

alter table content_items
    add column show_on_profile boolean not null default true;

alter table content_items
    add column profile_pinned_at timestamp;

alter table content_items
    add column profile_sort_order int not null default 0;

alter table collections
    add column visibility varchar(20) not null default 'private';

alter table collections
    add column updated_at timestamp not null default current_timestamp;

create unique index idx_users_handle on users(handle);
create index idx_content_profile on content_items(author_user_id, status, visibility, show_on_profile);
create index idx_follows_follower_created on follows(follower_id, created_at desc);
create index idx_follows_following_created on follows(following_id, created_at desc);
