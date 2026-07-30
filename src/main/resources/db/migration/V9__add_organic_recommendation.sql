create table recommendation_serve_sessions (
    id varchar(64) primary key,
    actor_key varchar(64) not null,
    kind varchar(20) not null,
    query_hash varchar(64),
    snapshot_json text not null,
    created_at timestamp not null default current_timestamp,
    expires_at timestamp not null
);

create table recommendation_client_requests (
    actor_key varchar(64) not null,
    endpoint varchar(40) not null,
    client_request_id varchar(128) not null,
    request_hash varchar(64) not null,
    response_status int not null,
    response_json text not null,
    created_at timestamp not null default current_timestamp,
    expires_at timestamp not null,
    primary key (actor_key, endpoint, client_request_id)
);

create table recommendation_client_events (
    actor_key varchar(64) not null,
    event_id varchar(128) not null,
    event_fingerprint varchar(64) not null,
    event_type varchar(40) not null,
    entity_id varchar(128),
    serve_session_id varchar(64),
    occurred_at_ms bigint not null,
    accepted_at timestamp not null default current_timestamp,
    primary key (actor_key, event_id)
);

create table recommendation_user_interests (
    actor_key varchar(64) not null,
    interest_key varchar(200) not null,
    weight double precision not null default 0,
    updated_at timestamp not null default current_timestamp,
    primary key (actor_key, interest_key)
);

create index idx_recommendation_sessions_actor_kind
    on recommendation_serve_sessions(actor_key, kind, created_at desc);

create index idx_recommendation_sessions_expiry
    on recommendation_serve_sessions(expires_at);

create index idx_recommendation_requests_expiry
    on recommendation_client_requests(expires_at);

create index idx_recommendation_events_session
    on recommendation_client_events(serve_session_id, accepted_at);

create index idx_recommendation_interests_actor_weight
    on recommendation_user_interests(actor_key, weight desc);
