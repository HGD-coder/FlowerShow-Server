create table accounts (
    id varchar(64) primary key,
    user_id varchar(64) not null,
    username varchar(80) not null,
    password_hash varchar(255) not null,
    role varchar(30) not null default 'creator',
    status varchar(20) not null default 'active',
    generated boolean not null default true,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uq_accounts_user unique (user_id),
    constraint uq_accounts_username unique (username),
    constraint fk_accounts_user foreign key (user_id) references users(id) on delete cascade
);

create index idx_accounts_username on accounts(username);
create index idx_accounts_role_status on accounts(role, status);
