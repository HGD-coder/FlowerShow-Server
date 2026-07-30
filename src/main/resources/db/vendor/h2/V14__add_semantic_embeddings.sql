create table content_embeddings (
    content_id varchar(64) not null,
    model_key varchar(128) not null,
    source_hash varchar(64) not null,
    dimensions int not null,
    embedding clob not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    primary key (content_id, model_key),
    constraint fk_content_embeddings_content
        foreign key (content_id) references content_items(id) on delete cascade
);

create table suggestion_embeddings (
    suggestion_id varchar(64) not null,
    suggestion_text varchar(200) not null,
    model_key varchar(128) not null,
    source_hash varchar(64) not null,
    dimensions int not null,
    embedding clob not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    primary key (suggestion_id, model_key)
);

create table user_interest_embeddings (
    actor_key varchar(64) not null,
    model_key varchar(128) not null,
    source_hash varchar(64) not null,
    dimensions int not null,
    embedding clob not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    primary key (actor_key, model_key)
);

create index idx_content_embeddings_model
    on content_embeddings(model_key, dimensions);

create index idx_suggestion_embeddings_model
    on suggestion_embeddings(model_key, dimensions);

create index idx_user_interest_embeddings_model
    on user_interest_embeddings(model_key, dimensions);
