# user-service

Identity and social graph for JTalk: **profiles, the language matrix, language-exchange matchmaking, follows and blocks.**
The [live-communication-service](https://github.com/Melkabli05/JTalk) holds only ephemeral room state and bare user UUIDs; this service is where
a UUID becomes a name, an avatar, the languages someone speaks and the people they follow.
Java 25 · Micronaut 5 · PostgreSQL · Redis.

## Structure

```
src/main/java/com/jtalk/userservice/
├── config/        UserConstants, UserProperties, ProblemType (RFC 9457 problems), UserIdClaimValidator
├── controller/    UserProfileController, SocialGraphController (+ @CurrentUser)
├── service/       UserService, LanguageService, SocialGraphService; impl/ with their implementations and CacheSyncListener
├── repository/    UserRepository, UserLanguageRepository, FollowRepository, BlockRepository (JDBC);
│                  UserRedisRepository (profile hashes), SocialGraphRedisRepository (follow sorted sets)
├── domain/        entity/ (User, UserLanguage), enums/, event/, model/
└── dto/           request/, response/ and ApiMapper (Micronaut @Mapper)
src/main/resources/  application.yml (+ cloud), db/migration/
```

`ArchitectureTest` enforces the shape (20 rules, on our own code, not Micronaut's generated classes):

- **Layering:** controller -> service -> repository -> domain, never upwards; `config` depends on nothing of ours; no package cycles; only service
  interfaces are used outside `service/impl`.
- **Placement and names:** controllers are `*Controller`, package-private and `@Secured`; service interfaces are `*Service` and `@Validated`;
  implementations are package-private; repositories are `*Repository`; `@MappedEntity` classes live in `domain/entity`; DTOs are records named
  `*Request` / `*Response` in `dto/request` / `dto/response`.
- **Hygiene:** constructor injection only, no `System.out`, no `java.util.logging`.

## How it works

**PostgreSQL is the source of truth; Redis only makes reads fast.** Tables live in the `user_service` schema (the Neon database is shared with the
live communication service): `users`, `user_languages`, `follows`, `blocks`.

- **Profile cache.** A profile is a Redis hash `user:profile:{id}` (24 h). `POST /users/batch` resolves a whole room (up to 500 ids) with one pipelined
  Redis read and one query for whatever was missing. A write goes to PostgreSQL in a transaction; after the commit the new profile is stored in Redis
  and a message `{"type":"USER_PROFILE_UPDATED","userId":...,"version":...}` is published on the Redis channel `user-events`, so other services can drop
  what they cached. The store is a small Lua script (`UserRedisRepository.PUT_IF_NEWER`): a slow reader that loaded an old profile can never overwrite a newer one. If Redis is
  down, reads fall back to PostgreSQL.
- **Follow graph.** `user:following:{id}` and `user:followers:{id}` are sorted sets (member = other user, score = follow time). "Does A follow B" is a
  `ZSCORE`, a count is a `ZCARD`, a page is a `ZREVRANGE`. A set is built from PostgreSQL on first use, in chunks, into a temporary key that is renamed
  into place, so it is either complete or absent; a follow is added only to a set that exists. Sets expire after 6 h and are rebuilt on demand.
- **Matchmaking.** `GET /users/matchmaking` returns users who are *native* in what you are learning and *learning* what you speak, minus anyone blocked
  either way, newest first, keyset-paginated.

## API (under `/api/v1/users`)

Every call needs `Authorization: Bearer <Auth0 access token>`; `me` is the user that token names (its user-id claim, a UUID, the same id the live
communication service uses). See [Authentication](#authentication-auth0).
Errors are RFC 9457 `application/problem+json` with a stable `type` such as `urn:jtalk:user:problem:username-taken`.

| Method & path | Purpose |
|---|---|
| `POST /me` / `GET /me` / `PATCH /me` | Create your profile (once; the country is optional) / read / partial update (`""` clears `bio` and `avatarUrl`; the username is fixed). `avatarUrl` is an `https` URL or a path of the front end such as `/avatars/avatar-03.svg` |
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
export AUTH0_DOMAIN=your-tenant.eu.auth0.com      # your Auth0 tenant, without https://
export AUTH0_AUDIENCE=https://api.jtalk.example   # the identifier of the API you created in Auth0
./gradlew run
./gradlew test                                   # needs no Auth0: the tests run a fake tenant (support/FakeAuth0)
```

Deployed: set `DATASOURCES_DEFAULT_URL/_USERNAME/_PASSWORD`, `REDIS_URI`, `AUTH0_DOMAIN` and `AUTH0_AUDIENCE`. The Docker image runs with the `cloud` profile
(`application-cloud.yml`: Neon and Upstash, hardcoded like the live service's, so those credentials are public; rotate them if misused). The Upstash
Redis is shared with the live communication service; this service only uses keys that start with `user:`. `render.yaml` is the Render blueprint.

## Authentication (Auth0)

Users log in with Auth0 (the Angular app uses Authorization Code with PKCE, e.g. `@auth0/auth0-angular`, and asks for an access token for this
API's audience). This service never sees a password and issues nothing: Micronaut Security checks each token's **RS256 signature against the tenant's
published keys** (JWKS, fetched and cached for you), its **issuer**, its **audience** and its **expiry**, all from `application.yml`. A token signed any
other way, including an HS256 one, is refused.

Auth0's `sub` (`auth0|64f...`) is not a UUID, but a user is a UUID here and in the live communication service. So the user's UUID travels in a
**custom claim** (`https://jtalk.app/user_id`, configurable with `AUTH0_USER_ID_CLAIM`), which Micronaut Security uses as the caller's name
(`micronaut.security.token.name-key`); a token without it, or with something that is not a UUID, is a 401.

One-time setup in the Auth0 dashboard:

1. **APIs > Create API**: name it, set the identifier (this is `AUTH0_AUDIENCE`, e.g. `https://api.jtalk.example`), signing algorithm **RS256**.
2. **Applications > Create Application > Single Page Application** for the Angular app; add its URLs (`http://localhost:4200` and the deployed one) to
   Allowed Callback URLs, Allowed Logout URLs and Allowed Web Origins.
3. **Actions > Library > Build Custom > Login / Post Login**, add the Action below to the Login flow. It gives each user one stable UUID and puts it
   in the access token:

```js
exports.onExecutePostLogin = async (event, api) => {
  let id = event.user.app_metadata.jtalk_id;
  if (!id) {
    id = require('crypto').randomUUID();
    api.user.setAppMetadata('jtalk_id', id);
  }
  api.accessToken.setCustomClaim('https://jtalk.app/user_id', id);
};
```

4. Set `AUTH0_DOMAIN` and `AUTH0_AUDIENCE` (Render asks for both). The live communication service needs the same two settings and the same claim to
   accept the same tokens.

A new user's first call is `POST /api/v1/users/me`, which creates their profile; until then `GET /api/v1/users/me` is a 404, which is the frontend's
cue to show "create your profile".

## Built-in first

Everything the framework provides is used as is: Micronaut Data (declarative JDBC repositories, `@DateCreated`/`@DateUpdated`, derived queries,
DTO projections), Security JWT, Validation, Problem JSON, Flyway, `@ConfigurationProperties`, `@TransactionalEventListener`, `@Mapper`, health
endpoints. The custom code that remains has no built-in equivalent:

- **Pipelined profile reads and the "never overwrite a newer profile" write** (`UserRedisRepository`): Micronaut Cache does one `GET` per key and
  cannot refuse an older value, so a full room would cost one round trip per participant.
- **The follow sorted sets** (`SocialGraphRedisRepository`) and the **pub/sub announcement**: Micronaut has no abstraction over either.
- **`@CurrentUser`**: Micronaut binds the `Principal` itself, whose name is a string; every endpoint needs it as a `UUID`, so one binder converts it instead of each of the 14 endpoints doing it.
- **`Violations`**: Micronaut Data has no exception type for a unique-constraint violation, so two simultaneous sign-ups still end in a 409.

## Known limits

- **The Auth0 tenant is not part of this repository.** Until the API, the application and the Action above exist, no token validates. There is no
  "security off" mode: to call the API locally, log in through the Angular app (`http://localhost:4200` is an allowed origin).
- **A very large follower list is rebuilt in full** (in chunks) when its Redis set is missing, and costs memory in proportion. Following is capped,
  followers are not.
- **A follow made while that user's set is being rebuilt can be missing from it** until the set expires (6 h) or any block drops it.
- **A Redis failure after a database commit is only logged**: the cached profile can stay stale until it expires (24 h). Follow sets are dropped instead.
- **Search is by prefix** (username or display name), not fuzzy.
- Several instances have not been exercised; nothing here is instance-local.
