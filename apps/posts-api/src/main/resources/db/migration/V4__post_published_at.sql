-- O Post passa a ter DUAS fases: pré-criado (existe, ainda sem a primeira tag) e criado (completo).
-- `published_at` é o que as distingue, e é nulo enquanto a saga de tagueamento não voltou.
--
-- POR QUE UMA COLUNA, e não derivar de `post_tags` estar vazia
-- ============================================================
-- Porque as duas coisas deixariam de ser distinguíveis no dia em que um post completo tiver a última
-- tag removida. "Nunca foi publicado" e "não tem tags agora" são fatos diferentes, e só um deles é
-- irreversível.
--
-- E por que um TIMESTAMP e não um boolean: o instante é a informação que alguém vai querer depois
-- (quanto tempo a saga levou, quando o post ficou visível); o boolean é a projeção pobre dele. A
-- coluna nasce nula em todo post existente, o que é a verdade para os posts criados antes desta
-- migration — eles nunca passaram pela fase de pré-criação.
alter table posts
    add column published_at timestamp(6) with time zone;

-- Os posts que já existem foram criados pelo fluxo antigo, em que criar era publicar.
update posts set published_at = created_at where published_at is null;
