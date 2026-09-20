alter table posts
    add column published_at timestamp(6) with time zone;

update posts set published_at = created_at where published_at is null;
