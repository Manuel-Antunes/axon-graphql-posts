create table axon_message_inbox (
    identifier   varchar(255) not null,
    message_type varchar(255) not null,
    origin       varchar(255),
    received_at  timestamp(6) with time zone not null,
    primary key (identifier)
);

create index idx_axon_message_inbox_received_at on axon_message_inbox (received_at);
