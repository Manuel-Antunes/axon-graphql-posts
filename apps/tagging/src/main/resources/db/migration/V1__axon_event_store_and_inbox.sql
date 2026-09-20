create sequence "aggregate-event-global-index-sequence"
    start with 1 increment by 1 cache 1;

create table aggregateevententry (
    globalindex             bigint       not null,
    aggregatetype           varchar(255),
    aggregateidentifier     varchar(255),
    aggregatesequencenumber bigint,
    type                    varchar(255) not null,
    version                 varchar(255) not null,
    "timestamp"             varchar(255) not null,
    payload                 oid          not null,
    metadata                oid,
    identifier              varchar(255) not null,
    primary key (globalindex)
);

alter table aggregateevententry
    add constraint uk_aggregateevententry_aggregate
    unique (aggregateidentifier, aggregatesequencenumber);

create table tokenentry (
    processorname varchar(255) not null,
    segment       integer      not null,
    token         oid,
    tokentype     varchar(255),
    "timestamp"   varchar(255) not null,
    owner         varchar(255),
    mask          integer      not null,
    primary key (processorname, segment)
);

create table axon_message_inbox (
    identifier   varchar(255) not null,
    message_type varchar(255) not null,
    origin       varchar(255),
    received_at  timestamp(6) with time zone not null,
    primary key (identifier)
);

create index idx_axon_message_inbox_received_at on axon_message_inbox (received_at);
