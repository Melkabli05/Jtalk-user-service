-- Does Postgres ever switch these statements to a generic plan? pg_prepared_statements counts custom vs generic plans.
set search_path to user_service;
\set QUIET on
show plan_cache_mode;
select id as viewer from users where native_language='en' and status='ACTIVE' limit 1 \gset
prepare match1(uuid, text, text, int) as select u.* from user_service.users u where u.status='ACTIVE' and u.id <> $1 and exists (select 1 from user_service.user_languages n where n.user_id=u.id and n.language_type='NATIVE' and n.language_code=$2) and exists (select 1 from user_service.user_languages l where l.user_id=u.id and l.language_type='LEARNING' and l.language_code=$3) and not exists (select 1 from user_service.blocks b where (b.blocker_id=$1 and b.blocked_id=u.id) or (b.blocker_id=u.id and b.blocked_id=$1)) order by u.created_at desc, u.id desc limit $4;
prepare usearch(uuid, text, text, int) as select u.* from user_service.users u where u.status='ACTIVE' and u.id <> $1 and u.username collate "C" >= $2 and u.username collate "C" < $3 and not exists (select 1 from user_service.blocks b where (b.blocker_id=$1 and b.blocked_id=u.id) or (b.blocker_id=u.id and b.blocked_id=$1)) order by u.username collate "C" limit $4;
\o /dev/null
-- 40 executions over varying language pairs and prefixes, as a busy pooled connection would see
select count(*) from (select 1 from generate_series(1,1)) _;
execute match1(:'viewer','ar','en',21); execute match1(:'viewer','es','fr',21); execute match1(:'viewer','ko','tr',21); execute match1(:'viewer','en','ja',21);
execute match1(:'viewer','ar','en',21); execute match1(:'viewer','de','pt',21); execute match1(:'viewer','ru','zh',21); execute match1(:'viewer','it','en',21);
execute match1(:'viewer','ar','en',21); execute match1(:'viewer','fr','es',21); execute match1(:'viewer','ja','ko',21); execute match1(:'viewer','tr','ar',21);
execute usearch(:'viewer','sa','sa􏿿',20); execute usearch(:'viewer','om','om􏿿',20); execute usearch(:'viewer','zq','zq􏿿',20); execute usearch(:'viewer','mo','mo􏿿',20);
execute usearch(:'viewer','al','al􏿿',20); execute usearch(:'viewer','ya','ya􏿿',20); execute usearch(:'viewer','ha','ha􏿿',20); execute usearch(:'viewer','ka','ka􏿿',20);
execute usearch(:'viewer','no','no􏿿',20); execute usearch(:'viewer','ma','ma􏿿',20); execute usearch(:'viewer','ad','ad􏿿',20); execute usearch(:'viewer','li','li􏿿',20);
\o
select name, generic_plans, custom_plans from pg_prepared_statements where name in ('match1','usearch') order by name;
