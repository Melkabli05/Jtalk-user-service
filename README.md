# user-service

Identity and social graph for JTalk: **profiles, the language matrix, language-exchange matchmaking, follows and blocks.**
The [live-communication-service](https://github.com/Melkabli05/JTalk) holds only ephemeral room state and bare user UUIDs; this service is where
a UUID becomes a name, an avatar, the languages someone speaks and the people they follow.
Java 25 · Micronaut 5 · PostgreSQL · Redis.

## Structure

```
src/main/java/com/jtalk/userservice/
├── config/        UserConstants, UserProperties, ProblemType (RFC 9457 problems), UuidSubjectClaimsValidator, ClockFactory
├── controller/    UserProfileController, SocialGraphController (+ @CurrentUser)
├── service/       UserService, LanguageService, SocialGraphService; impl/ with their implementations and CacheSyncListener
├── repository/    UserRepository, UserLanguageRepository, FollowRepository, BlockRepository (JDBC);
│                  UserRedisRepository (profile hashes), SocialGraphRedisRepository (follow sorted sets), Lua scripts
├── domain/        entity/ (User, UserLanguage), enums/, event/, model/
└── dto/           request/, response/ and ApiMapper (MapStruct)
src/main/resources/  application.yml (+ cloud), db/migration/, lua/
```

`ArchitectureTest` enforces the layering (controller -> service -> repository -> domain, never upwards; only interfaces outside `service/impl`).

## How it works

**PostgreSQL is the source of truth; Redis only makes reads fast.** Tables live in the `user_service` schema (the Neon database is shared with the
live communication service): `users`, `user_languages`, `follows`, `blocks`.

- **Profile cache.** A profile is a Redis hash `user:profile:{id}` (24 h). `POST /users/batch` resolves a whole room (up to 500 ids) with one pipelined
  Redis read and one query for whatever was missing. A write goes to PostgreSQL in a transaction; after the commit the new profile is stored in Redis
  and a message `{"type":"USER_PROFILE_UPDATED","userId":...,"version":...}` is published on the Redis channel `user-events`, so other services can drop
  what they cached. The store is `put_profile_if_newer.lua`: a slow reader that loaded an old profile can never overwrite a newer one. If Redis is
  down, reads fall back to PostgreSQL.
- **Follow graph.** `user:following:{id}` and `user:followers:{id}` are sorted sets (member = other user, score = follow time). "Does A follow B" is a
  `ZSCORE`, a count is a `ZCARD`, a page is a `ZREVRANGE`. A set is built from PostgreSQL on first use, in chunks, into a temporary key that is renamed
  into place, so it is either complete or absent; a follow is added only to a set that exists. Sets expire after 6 h and are rebuilt on demand.
- **Matchmaking.** `GET /users/matchmaking` returns users who are *native* in what you are learning and *learning* what you speak, minus anyone blocked
  either way, newest first, keyset-paginated.

## API (under `/api/v1/users`)

Every call needs `Authorization: Bearer <JWT>` whose `sub` is the user's UUID (the same id the live communication service uses); `me` is that user.
Errors are RFC 9457 `application/problem+json` with a stable `type` such as `urn:jtalk:user:problem:username-taken`.

| Method & path | Purpose |
|---|---|
| `POST /me` / `GET /me` / `PATCH /me` | Create your profile (once) / read / partial update (`""` clears `bio` and `avatarUrl`; the username is fixed) |
| `GET /{id}` | Any user's profile |
| `POST /batch` `{ids}` | Summaries (name, avatar, country, language) for up to 500 ids, in the order asked; unknown ids are left out |
| `GET /search?q=&size=` | Username or display-name prefix; never yourself, suspended users, or anyone blocked either way |
| `GET /matchmaking?nativeLang=&learningLang=&size=&afterCreatedAt=&afterId=` | Language-exchange partners; the languages default to yours |
| `GET /me/languages`, `GET /{id}/languages` | The matrix: native languages and learning languages with levels |
| `POST /me/languages` `{languageCode, languageType, proficiencyLevel}` / `DELETE /me/languages/{code}` | List a language (or change a level) / remove one (not your primary native language) |
| `POST /{id}/follow` / `DELETE /{id}/follow` | Idempotent, `204` |
| `GET /{id}/followers`, `GET /{id}/following` `?page=&size=` | Newest follow first, with `total` |
| `GET /{id}/social` | `followerCount`, `followingCount`, and `followedByMe` / `followsMe` |
| `POST /{id}/block` / `DELETE /{id}/block` / `GET /me/blocks` | A block removes follows both ways and forbids new ones |

A language is native *or* learning for a user, never both. Following is capped (`user.max-following`, default 5000) and so is the matrix
(`user.max-languages`, default 20).

## Running

Needs JDK 25 and Docker; PostgreSQL and Redis are started by Micronaut Test Resources for `./gradlew run` and `./gradlew test`.

```bash
export JWT_SECRET=...                       # 32+ characters; the same value the live communication service verifies with
./gradlew run
MICRONAUT_SECURITY_ENABLED=false ./gradlew run   # local work only: no token, the caller is named by the X-User-Id header
./gradlew test
```

Deployed: set `DATASOURCES_DEFAULT_URL/_USERNAME/_PASSWORD`, `REDIS_URI` and `JWT_SECRET`. The Docker image runs with the `cloud` profile
(`application-cloud.yml`: Neon and Upstash, hardcoded like the live service's, so those credentials are public; rotate them if misused). The Upstash
Redis is shared with the live communication service; this service only uses keys that start with `user:`. `render.yaml` is the Render blueprint.

## Known limits

- **There is no sign-up or login.** This service verifies tokens (`JWT_SECRET`) but does not issue them, and a user creates their profile with
  `POST /me` after authenticating elsewhere. Until something issues tokens, run with security off (`X-User-Id`), which lets anyone act as anyone.
- **A very large follower list is rebuilt in full** (in chunks) when its Redis set is missing, and costs memory in proportion. Following is capped,
  followers are not.
- **A follow made while that user's set is being rebuilt can be missing from it** until the set expires (6 h) or any block drops it.
- **A Redis failure after a database commit is only logged**: the cached profile can stay stale until it expires (24 h). Follow sets are dropped instead.
- **Search is by prefix** (username or display name), not fuzzy.
- Several instances have not been exercised; nothing here is instance-local except the Lua script cache, which heals itself.
