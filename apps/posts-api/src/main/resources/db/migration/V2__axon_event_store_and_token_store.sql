-- O event store deixa de ser em memória e passa a ser o Postgres, e os event processors passam a ter
-- onde guardar até onde leram. As duas tabelas são do Axon, não do domínio: quem as mapeia são
-- `AggregateEventEntry` e `TokenEntry`, de dentro dos jars.
--
-- POR QUE ESTE ARQUIVO EXISTE, se as entidades são de biblioteca
-- ==============================================================
-- Porque o Hibernate roda em `schema-management.strategy=validate`: entidade no classpath sem tabela
-- não sobe. Acrescentar o `quarkus-axon-jpa-eventstore` sem esta migration derruba a aplicação com
-- `Schema validation: missing table [AggregateEventEntry]`.
--
-- E o DDL daqui NÃO foi escrito de cabeça: foi extraído com `pg_dump` de um banco descartável depois
-- de subir a aplicação com `drop-and-create`. Três detalhes seriam impossíveis de adivinhar e
-- quebrariam a validação em silêncio:
--   1. os nomes ficam TODOS MINÚSCULOS E SEM UNDERSCORE (`aggregateevententry`, `processorname`) —
--      a entidade é de biblioteca e não passa pela convenção do resto do schema;
--   2. a sequence tem HÍFENS e portanto exige aspas (`"aggregate-event-global-index-sequence"`), com
--      `allocationSize = 1` declarado na entidade;
--   3. `timestamp` é nome de tipo no Postgres e também é o nome de coluna nas duas tabelas.

create sequence "aggregate-event-global-index-sequence"
    start with 1 increment by 1 cache 1;

-- O stream. Append-only: nada aqui é atualizado ou apagado, e é isso que permite reidratar um agregado
-- lendo as linhas dele em ordem de `aggregatesequencenumber`.
--
-- ATENÇÃO AO TIPO `oid` de `payload`/`metadata`: é Large Object do Postgres (o conteúdo vive em
-- `pg_largeobject` e a coluna guarda só a referência), porque o Axon anota os campos com `@Lob byte[]`
-- e é assim que o Hibernate mapeia isso no Postgres. Não é escolha nossa e tem consequências
-- operacionais reais: **replicação lógica não replica Large Objects**, então CDC (Debezium, por
-- exemplo) sobre esta tabela traria as linhas sem o payload. Se isso for necessário, o conserto é na
-- entidade do Axon (`@JdbcTypeCode(VARBINARY)`, que daria `bytea`), não aqui — mudar o tipo nesta
-- migration faria a validação do Hibernate divergir do mapeamento.
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

-- A unicidade que dá a garantia de concorrência otimista do event sourcing: dois appends disputando a
-- mesma posição do mesmo agregado, um perde. Vem de `@Table(indexes = @Index(..., unique = true))` na
-- entidade — sem ela, uma escrita concorrente duplicaria a sequência em vez de falhar.
alter table aggregateevententry
    add constraint uk_aggregateevententry_aggregate
    unique (aggregateidentifier, aggregatesequencenumber);

-- Onde cada event processor guarda a posição de leitura. A chave é (processador, segmento): um
-- `PooledStreamingEventProcessor` divide o stream em segmentos e cada um caminha por conta própria.
--
-- `owner` e `timestamp` são o mecanismo de claim: um nó pega o segmento, estampa o horário e renova.
-- Se ele morrer, o claim expira e outro nó assume — é o que faz a retomada funcionar sem coordenação
-- externa. É também a razão de a posição viver AQUI e não no ack do broker: ack dá entrega
-- at-least-once, não "recomece do evento N".
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
