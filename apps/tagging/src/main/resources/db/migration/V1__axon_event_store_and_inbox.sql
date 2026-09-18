-- O schema DESTE serviço. Ele é pequeno de propósito: três tabelas, todas de infraestrutura.
--
-- POR QUE UM BANCO SEPARADO DO SERVIÇO DE POSTS
-- =============================================
-- Porque event store compartilhado é acoplamento pela porta dos fundos. Dois serviços no mesmo store
-- podem ler o stream um do outro sem passar por contrato nenhum — e no dia em que um deles fizer isso,
-- a coreografia acabou sem ninguém ter decidido. Bancos separados fazem a única forma de um saber algo
-- do outro ser a mensagem.
--
-- O preço é honesto: mais um banco para operar. Aqui eles moram no mesmo container do Postgres, o que é
-- exatamente o que uma infraestrutura compartilhada faria — instâncias separadas custariam memória sem
-- provar nada a mais.
--
-- O DDL das duas primeiras tabelas é de entidades de DENTRO dos jars do Axon (`AggregateEventEntry`,
-- `TokenEntry`) e foi extraído com pg_dump, não escrito de cabeça. Três detalhes seriam impossíveis de
-- adivinhar: os nomes ficam todos minúsculos e sem underscore, a sequence tem hífens (e portanto exige
-- aspas), e `timestamp` é nome de tipo no Postgres além de nome de coluna.

create sequence "aggregate-event-global-index-sequence"
    start with 1 increment by 1 cache 1;

-- O stream deste serviço: um evento `DefaultTagAssigned` por post decidido, mais os eventos que ele
-- INGERE de fora (`PostPreCreated`). Os dois convivem na mesma tabela de propósito — o evento ingerido
-- é gravado antes de ser processado, e é isso que faz o passo da saga sobreviver a um restart.
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

-- A idempotência da ingestão. Mesma tabela, mesmo motivo e mesma explicação da V3 do serviço de posts:
-- broker nenhum entrega exatamente uma vez, e apendar o mesmo evento duas vezes no stream é estado
-- errado, não desperdício. A linha é gravada no MESMO commit do append.
create table axon_message_inbox (
    identifier   varchar(255) not null,
    message_type varchar(255) not null,
    origin       varchar(255),
    received_at  timestamp(6) with time zone not null,
    primary key (identifier)
);

create index idx_axon_message_inbox_received_at on axon_message_inbox (received_at);
