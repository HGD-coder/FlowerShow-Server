alter table media_assets
    add column delivery_type varchar(20) not null default 'progressive';

alter table media_assets
    add column container_format varchar(20);

alter table media_assets
    add column codec varchar(80);

create index idx_media_assets_delivery on media_assets(content_id, kind, delivery_type, quality, sort_order);
