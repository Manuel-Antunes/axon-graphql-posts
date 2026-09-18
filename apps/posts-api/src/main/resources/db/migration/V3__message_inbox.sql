-- A tabela de idempotência da INGESTÃO: o que este serviço já apendou no event store dele a partir de
-- uma mensagem vinda do broker.
--
-- POR QUE ELA EXISTE, se a mensagem já traz um identificador único
-- ================================================================
-- Porque broker nenhum entrega exatamente uma vez. RabbitMQ garante *ao menos* uma: um nack, um
-- restart do consumidor antes do ack, ou um requeue por timeout reentregam a MESMA mensagem. Sem esta
-- tabela, a segunda entrega apenderia o evento no stream de novo — e um stream com o evento duplicado
-- não é um detalhe de performance: é estado errado, porque o agregado é reidratado somando eventos.
--
-- A metadata do envelope NÃO serve para isto. Ela diz de onde a mensagem veio (e é o que corta o
-- laço de reenvio), mas ela viaja COM a mensagem: a reentrega traz a mesma metadata. Quem sabe que
-- "esta eu já processei" é o serviço, e a memória dele tem de sobreviver ao restart — ou seja, tem de
-- ser uma linha no banco, no MESMO commit do append. É por isso que a tabela é aqui e não um cache.
--
-- `identifier` é a chave primária de propósito: a segunda inserção falha por violação de unicidade e
-- essa falha É a detecção. Não há select-depois-insert com janela de corrida entre nós concorrentes.
create table axon_message_inbox (
    identifier   varchar(255) not null,
    message_type varchar(255) not null,
    origin       varchar(255),
    received_at  timestamp(6) with time zone not null,
    primary key (identifier)
);

-- Para a limpeza operacional (o inbox cresce para sempre se ninguém apagar) e para responder "o que
-- chegou na última hora" sem varrer a tabela.
create index idx_axon_message_inbox_received_at on axon_message_inbox (received_at);
