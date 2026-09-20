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

create unique index uk_users_email_active
    on users (email)
    where superseded_by is null and deleted_at is null;

create index ix_users_superseded_by on users (superseded_by) where superseded_by is not null;

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

create table accounts (
    id            varchar(36)                 not null,
    user_id       varchar(36)                 not null,
    provider      varchar(32)                 not null,
    subject       varchar(255)                not null,
    password_hash varchar(255),
    linked_at     timestamp(6) with time zone not null,
    primary key (id),
    constraint ck_accounts_provider check (provider in ('CREDENTIAL', 'KEYCLOAK', 'GOOGLE', 'GITHUB')),
    constraint uk_accounts_provider_subject unique (provider, subject),
    constraint fk_accounts_user foreign key (user_id) references users (id)
);

create index ix_accounts_user on accounts (user_id);

create table tags (
    id         varchar(36)                 not null,
    name       varchar(50)                 not null,
    created_at timestamp(6) with time zone not null,
    primary key (id),
    constraint uk_tags_name unique (name)
);

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

create index ix_posts_alive_created on posts (created_at, id) where deleted_at is null;

create index ix_posts_author_recent on posts (author_id, created_at desc) where deleted_at is null;

create table post_tags (
    post_id varchar(36) not null,
    tag_id  varchar(36) not null,
    primary key (post_id, tag_id),
    constraint fk_post_tags_post foreign key (post_id) references posts (id),
    constraint fk_post_tags_tag foreign key (tag_id) references tags (id)
);

create index ix_post_tags_tag on post_tags (tag_id);
