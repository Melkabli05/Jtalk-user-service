-- The shipped queries under EXPLAIN ANALYZE, with the generic plan forced (worst case); the second block prints the warm numbers.
set search_path to user_service;
\set QUIET on
select id as viewer from users where native_language='en' and status='ACTIVE' limit 1 \gset
select created_at as cur_ts, id as cur_id from users u where native_language='ar' and status='ACTIVE' order by created_at desc offset 4000 limit 1 \gset
select following_id as star from follows group by 1 order by count(*) desc limit 1 \gset
select now() + interval '1 year' as far \gset
select array_agg(id) as ids200 from (select id from users order by random() limit 200) s \gset
prepare match1(uuid, text, text, int) as select u.* from user_service.users u where u.status='ACTIVE' and u.id <> $1 and exists (select 1 from user_service.user_languages n where n.user_id=u.id and n.language_type='NATIVE' and n.language_code=$2) and exists (select 1 from user_service.user_languages l where l.user_id=u.id and l.language_type='LEARNING' and l.language_code=$3) and not exists (select 1 from user_service.blocks b where (b.blocker_id=$1 and b.blocked_id=u.id) or (b.blocker_id=u.id and b.blocked_id=$1)) order by u.created_at desc, u.id desc limit $4;
prepare match2(uuid, text, text, timestamptz, uuid, int) as select u.* from user_service.users u where u.status='ACTIVE' and u.id <> $1 and exists (select 1 from user_service.user_languages n where n.user_id=u.id and n.language_type='NATIVE' and n.language_code=$2) and exists (select 1 from user_service.user_languages l where l.user_id=u.id and l.language_type='LEARNING' and l.language_code=$3) and not exists (select 1 from user_service.blocks b where (b.blocker_id=$1 and b.blocked_id=u.id) or (b.blocker_id=u.id and b.blocked_id=$1)) and (u.created_at, u.id) < ($4, $5) order by u.created_at desc, u.id desc limit $6;
prepare usearch(uuid, text, text, int) as select u.* from user_service.users u where u.status='ACTIVE' and u.id <> $1 and u.username collate "C" >= $2 and u.username collate "C" < $3 and not exists (select 1 from user_service.blocks b where (b.blocker_id=$1 and b.blocked_id=u.id) or (b.blocker_id=u.id and b.blocked_id=$1)) order by u.username collate "C" limit $4;
prepare dsearch(uuid, text, text, int) as select u.* from user_service.users u where u.status='ACTIVE' and u.id <> $1 and lower(u.display_name) collate "C" >= $2 and lower(u.display_name) collate "C" < $3 and not exists (select 1 from user_service.blocks b where (b.blocker_id=$1 and b.blocked_id=u.id) or (b.blocker_id=u.id and b.blocked_id=$1)) order by lower(u.display_name) collate "C", u.id limit $4;
prepare fchunk(uuid, timestamptz, uuid, int) as select follower_id as user_id, created_at from user_service.follows where following_id = $1 and (created_at, follower_id) < ($2, $3) order by created_at desc, follower_id desc limit $4;
prepare byids(uuid[]) as select * from user_service.users where id = any($1);
set plan_cache_mode = force_generic_plan;
\echo 'RESULT label | execution ms (generic prepared plan, the worst case a pooled connection ends up with)'
\o /dev/null
explain (analyze, costs off, summary on) execute match1(:'viewer','ar','en',21);
explain (analyze, costs off, summary on) execute match2(:'viewer','ar','en',:'cur_ts',:'cur_id',21);
explain (analyze, costs off, summary on) execute match1(:'viewer','ko','tr',21);
explain (analyze, costs off, summary on) execute usearch(:'viewer','sa','sa􏿿',20);
explain (analyze, costs off, summary on) execute usearch(:'viewer','zqx','zqx􏿿',20);
explain (analyze, costs off, summary on) execute dsearch(:'viewer','sar','sar􏿿',20);
explain (analyze, costs off, summary on) execute fchunk(:'star',:'far','ffffffff-ffff-ffff-ffff-ffffffffffff',1000);
explain (analyze, costs off, summary on) execute byids(:'ids200');
\o
\echo '--- run again, warm, printing'
\echo 'matchmaking page 1 (ar->en, 13.7k matches)'
explain (analyze, costs off, summary on) execute match1(:'viewer','ar','en',21);
\echo 'matchmaking deep page'
explain (analyze, costs off, summary on) execute match2(:'viewer','ar','en',:'cur_ts',:'cur_id',21);
\echo 'matchmaking rare pair (ko->tr, 4.5k matches)'
explain (analyze, costs off, summary on) execute match1(:'viewer','ko','tr',21);
\echo 'search handle "sa" (common)'
explain (analyze, costs off, summary on) execute usearch(:'viewer','sa','sa􏿿',20);
\echo 'search handle "zqx" (no match)'
explain (analyze, costs off, summary on) execute usearch(:'viewer','zqx','zqx􏿿',20);
\echo 'search display name "sar" (20k matches)'
explain (analyze, costs off, summary on) execute dsearch(:'viewer','sar','sar􏿿',20);
\echo 'followers rebuild chunk (most followed)'
explain (analyze, costs off, summary on) execute fchunk(:'star',:'far','ffffffff-ffff-ffff-ffff-ffffffffffff',1000);
\echo 'batch 200 ids (= ANY)'
explain (analyze, costs off, summary on) execute byids(:'ids200');
