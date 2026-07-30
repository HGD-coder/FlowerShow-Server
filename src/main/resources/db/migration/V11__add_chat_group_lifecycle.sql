alter table chat_conversations
    add column conversation_state varchar(20) not null default 'active';

alter table chat_conversations
    add column dissolved_at timestamp;

alter table chat_conversations
    add column dissolved_by_user_id varchar(64);

alter table chat_conversations
    add constraint fk_chat_conversation_dissolved_by foreign key (dissolved_by_user_id)
        references users(id);

alter table chat_conversations
    add constraint ck_chat_conversation_state check (
        conversation_state in ('active', 'dissolved')
    );

alter table chat_conversations
    add constraint ck_chat_conversation_lifecycle check (
        (
            conversation_state = 'active'
            and dissolved_at is null
            and dissolved_by_user_id is null
        )
        or
        (
            conversation_type = 'group'
            and conversation_state = 'dissolved'
            and dissolved_at is not null
            and dissolved_by_user_id is not null
        )
    );
