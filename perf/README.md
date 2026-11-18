# Performance harness

SQL to reproduce the numbers in the main README ("Performance"). It needs a throwaway PostgreSQL, never a real database.

```bash
docker run -d --name perf-pg -e POSTGRES_PASSWORD=perf -e POSTGRES_DB=perf -p 55432:5432 postgres:18
P="docker exec -i perf-pg psql -q -v ON_ERROR_STOP=1 -U postgres -d perf"
echo "create schema user_service;" | $P
( echo "set search_path to user_service;"; cat ../src/main/resources/db/migration/V*.sql ) | $P   # run from this folder
$P < 01-seed-users.sql && $P < 02-seed-graph.sql                                                   # about 40 s
$P < 03-explain.sql                                                                                # timings and plans
$P < 04-plan-cache.sql                                                                             # generic vs custom plans
docker rm -f perf-pg
```
