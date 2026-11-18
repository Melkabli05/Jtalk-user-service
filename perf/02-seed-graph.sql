-- 220k blocks and 1.6M follows (a few very popular accounts).
set search_path to user_service;
create temp table u_num as select id, row_number() over () n from users;
create unique index on u_num(n);
analyze u_num;
with p as materialized (
  select 1 + floor(random()*500000)::int a, 1 + floor(random()*500000)::int b from generate_series(1, 220000)
)
insert into blocks (blocker_id, blocked_id, created_at)
select x.id, y.id, now() from p join u_num x on x.n = p.a join u_num y on y.n = p.b where x.id <> y.id
on conflict do nothing;
with p as materialized (
  select 1 + floor(random()*500000)::int a, 1 + floor(power(random(), 3) * 500000)::int b from generate_series(1, 1600000)
)
insert into follows (follower_id, following_id, created_at)
select x.id, y.id, now() - random() * interval '365 days' from p join u_num x on x.n = p.a join u_num y on y.n = p.b where x.id <> y.id
on conflict do nothing;
analyze;
select (select count(*) from users) users, (select count(*) from user_languages) languages, (select count(*) from blocks) blocks, (select count(*) from follows) follows,
       pg_size_pretty(pg_database_size('perf')) db_size;
