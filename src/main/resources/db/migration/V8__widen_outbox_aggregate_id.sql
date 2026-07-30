alter table outbox_events
    alter column aggregate_id type varchar(160);
