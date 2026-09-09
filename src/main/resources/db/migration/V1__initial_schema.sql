-- =====================================================================================================
-- Schema inicial.
--
-- Gerado a partir do mapeamento JPA (schema-generation do Hibernate) e depois curado à mão: nomes de
-- constraint legíveis no lugar dos hashes do Hibernate, índices para as consultas que existem de fato, e
-- um índice único parcial que o `ddl-auto` não tinha como expressar.
--
-- A partir daqui o `ddl-auto` é `validate`: o Hibernate confere que este arquivo e as entidades dizem a
-- mesma coisa, e a aplicação não sobe se divergirem. É a troca inteira — antes o schema era efeito
-- colateral do mapeamento e ninguém sabia o que rodava em produção; agora ele é o arquivo, e o mapeamento
-- é conferido contra ele.
-- =====================================================================================================


-- ---- Identidade -------------------------------------------------------------------------------------

-- Raiz da hierarquia table-per-type. `readers` e `authors` compartilham esta chave primária.
--
-- superseded_by / supersedes: o par de uma promoção. O tipo concreto de uma entidade polimórfica do Axon
-- é fixo desde o primeiro evento, então promover um leitor é encerrar o agregado dele e abrir outro —
-- e as duas colunas ligam os dois streams nos dois sentidos.
create table users (
    id            varchar(36)                 not null,
    email         varchar(254)                not null,
    name          varchar(80)                 not null,
    created_at    timestamp(6) with time zone not null,
    deleted_at    timestamp(6) with time zone,
    superseded_by varchar(36),
    supersedes    varchar(36),
    primary key (id)
);

-- O e-mail NÃO é único na tabela: um leitor encerrado e o autor que o substituiu convivem com o mesmo
-- e-mail, e um usuário apagado não deveria impedir um cadastro novo.
--
-- Mas ele precisa ser único entre os ATIVOS, senão `findByEmail` viraria uma escolha entre duas linhas.
-- Um índice parcial diz exatamente isso, e é a razão prática de sair do ddl-auto: nenhuma anotação JPA
-- expressa "único onde estas duas colunas são nulas".
create unique index uk_users_email_active
    on users (email)
    where superseded_by is null and deleted_at is null;

-- Quase toda consulta de usuário filtra por e-mail entre os vivos; o índice acima já serve de índice de
-- busca. Este cobre o caminho da retomada de promoção, que procura justamente os encerrados.
create index ix_users_superseded_by on users (superseded_by) where superseded_by is not null;

-- Subclasse sem estado próprio: a linha existir É a afirmação de que o usuário é um leitor.
create table readers (
    id varchar(36) not null,
    primary key (id),
    constraint fk_readers_user foreign key (id) references users (id)
);

create table authors (
    id  varchar(36)  not null,
    bio varchar(280) not null,
    primary key (id),
    constraint fk_authors_user foreign key (id) references users (id)
);

-- Credencial: identidade em `users`, "como prova que é ela" aqui, uma linha por provedor.
--
-- O CHECK do provider vem do enum AuthProvider e é deliberado: acrescentar um provedor passa a exigir uma
-- migration, que é o mesmo compromisso que o enum fechado já assume no código.
create table accounts (
    id            varchar(36)                 not null,
    user_id       varchar(36)                 not null,
    provider      varchar(32)                 not null,
    subject       varchar(255)                not null,
    password_hash varchar(255),
    linked_at     timestamp(6) with time zone not null,
    primary key (id),
    constraint ck_accounts_provider check (provider in ('CREDENTIAL', 'KEYCLOAK', 'GOOGLE', 'GITHUB')),
    -- impede a mesma conta do provedor de pertencer a dois usuários locais, que seria a maneira mais
    -- silenciosa de duas identidades virarem uma
    constraint uk_accounts_provider_subject unique (provider, subject),
    constraint fk_accounts_user foreign key (user_id) references users (id)
);

-- O `join fetch u.accounts` das três consultas de usuário passa por aqui.
create index ix_accounts_user on accounts (user_id);


-- ---- Conteúdo ---------------------------------------------------------------------------------------

create table tags (
    id         varchar(36)                 not null,
    name       varchar(50)                 not null,
    created_at timestamp(6) with time zone not null,
    primary key (id),
    constraint uk_tags_name unique (name)
);

-- content é TEXT e não um large object: o corpo de um post é texto comum, e @Lob no Postgres viraria um
-- OID à parte.
--
-- author_id referencia `authors` e não `users` porque Post.author é tipado como Author: só quem tem linha
-- na tabela filha pode escrever, e o banco garante isso junto com o @PreAuthorize.
create table posts (
    id         varchar(36)                 not null,
    title      varchar(200)                not null,
    content    text                        not null,
    author_id  varchar(36)                 not null,
    version    bigint                      not null,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    deleted_at timestamp(6) with time zone,
    primary key (id),
    constraint fk_posts_author foreign key (author_id) references authors (id)
);

-- A listagem paginada ordena por (created_at, id) e filtra os apagados. O índice parcial cobre os dois de
-- uma vez e não carrega as linhas que a consulta nunca vai ver.
create index ix_posts_alive_created on posts (created_at, id) where deleted_at is null;

-- O lote de Author.posts: por autor, mais recentes primeiro.
create index ix_posts_author_recent on posts (author_id, created_at desc) where deleted_at is null;

-- Relacionamento post ↔ tag. Sem cascade do lado do Post: o agregado escreve só estas linhas, nunca em
-- `tags`.
create table post_tags (
    post_id varchar(36) not null,
    tag_id  varchar(36) not null,
    primary key (post_id, tag_id),
    constraint fk_post_tags_post foreign key (post_id) references posts (id),
    constraint fk_post_tags_tag foreign key (tag_id) references tags (id)
);

-- A chave primária já indexa (post_id, tag_id); este cobre o sentido inverso, "quais posts têm esta tag".
create index ix_post_tags_tag on post_tags (tag_id);
