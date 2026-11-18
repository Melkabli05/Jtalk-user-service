-- 500k users with a skewed language mix (psql < this file, after migrations V1-V3 in the user_service schema).
set search_path to user_service;
\timing off
-- 500k users, created over ~2 years, usernames/display names with realistic shared prefixes
insert into users (id, username, display_name, bio, avatar_url, country_code, native_language, status, created_at, updated_at)
select gen_random_uuid(),
       (array['sara','sam','omar','mona','ali','lina','yusuf','hana','karim','nour','maya','adam'])[1 + (g % 12)] || '_' || substr(md5(g::text), 1, 6) || g,
       (array['Sara','Sam','Omar','Mona','Ali','Lina','Yusuf','Hana','Karim','Nour','Maya','Adam'])[1 + ((g * 7) % 12)] || ' ' || (array['Khan','Haddad','Smith','Garcia','Tanaka','Mori','Ibrahim','Costa'])[1 + (g % 8)],
       null, '/avatars/avatar-0' || (1 + g % 9) || '.svg', null,
       (array['en','ar','es','fr','de','ja','pt','ru','zh','ko','it','tr'])[1 + (g % 12)],
       case when g % 200 = 0 then 'SUSPENDED' else 'ACTIVE' end,
       now() - (random() * interval '730 days'), now()
from generate_series(1, 500000) g;
-- everybody is native in their native_language
insert into user_languages (id, user_id, language_code, language_type, proficiency_level)
select gen_random_uuid(), id, native_language, 'NATIVE', null from users;
-- 1 or 2 learning languages each, skewed towards English and Arabic, never their own
insert into user_languages (id, user_id, language_code, language_type, proficiency_level)
select gen_random_uuid(), u.id, l.code, 'LEARNING', 'BEGINNER'
from users u
join lateral (
  select code from unnest(array['en','en','en','ar','ar','es','fr','de','ja','pt','ru','zh','ko','it','tr']) code
  where code <> u.native_language order by random() limit 1 + (abs(hashtext(u.id::text)) % 2)
) l on true
on conflict do nothing;
-- 200k blocks and 1.5M follows
insert into blocks (blocker_id, blocked_id, created_at)
select a.id, b.id, now() from (select id, row_number() over () rn from users order by random() limit 200000) a
join (select id, row_number() over () rn from users order by random() limit 200000) b using (rn) where a.id <> b.id on conflict do nothing;
insert into follows (follower_id, following_id, created_at)
select a.id, b.id, now() - random() * interval '365 days' from (select id, row_number() over () rn from users order by random() limit 750000) a
join (select id, row_number() over () rn from users order by random() limit 750000) b using (rn) where a.id <> b.id on conflict do nothing;
analyze;
select (select count(*) from users) users, (select count(*) from user_languages) languages, (select count(*) from blocks) blocks, (select count(*) from follows) follows;
