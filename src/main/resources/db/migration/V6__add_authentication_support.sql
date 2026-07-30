alter table accounts
    add column failed_login_attempts int not null default 0;

alter table accounts
    add column locked_until timestamp;

alter table accounts
    add column last_login_at timestamp;

create table auth_refresh_tokens (
    id varchar(64) primary key,
    account_id varchar(64) not null,
    token_hash varchar(64) not null,
    expires_at timestamp not null,
    revoked_at timestamp,
    replaced_by_token_id varchar(64),
    created_at timestamp not null default current_timestamp,
    constraint uq_auth_refresh_token_hash unique (token_hash),
    constraint fk_auth_refresh_account foreign key (account_id) references accounts(id) on delete cascade,
    constraint fk_auth_refresh_replacement foreign key (replaced_by_token_id) references auth_refresh_tokens(id) on delete set null
);

create index idx_auth_refresh_account_active
    on auth_refresh_tokens(account_id, revoked_at, expires_at);

create index idx_accounts_status_locked
    on accounts(status, locked_until);
