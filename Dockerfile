# syntax=docker/dockerfile:1
#
# Generated with the Micronaut Gradle plugin (`./gradlew dockerfile`, which writes build/docker/main/Dockerfile) and made
# self-contained, as the plugin's docs suggest: the first stage runs `buildLayers` (the layered jar the generated
# Dockerfile copies from), the second is the generated runtime stage, on the same base image build.gradle.kts sets.
#
#   docker build -t user-service .
#   docker run --rm -p 8080:8080 --env-file .env user-service
#
# The image runs with the "cloud" profile (src/main/resources/application-cloud.yml: the hosted PostgreSQL on Neon, hardcoded, shared
# with the live communication service), so it only needs the Auth0 settings below. Override MICRONAUT_ENVIRONMENTS to point it elsewhere, and
# set the variables below instead.
#
# Configuration comes from the environment (see src/main/resources/application.yml):
#   AUTH0_DOMAIN, AUTH0_AUDIENCE                       the Auth0 tenant (e.g. your-tenant.eu.auth0.com) and this API's identifier
#   AUTH0_USER_ID_CLAIM                                optional: the access-token claim holding the user's UUID (default https://jtalk.app/user_id)
#   DATASOURCES_DEFAULT_URL / _USERNAME / _PASSWORD    PostgreSQL
#   REDIS_URI                                          Redis (profile cache and follow graph)
#   FLYWAY_DATASOURCES_DEFAULT_URL                    optional: a direct (non-pooled) URL for the migrations
#   CORS_ALLOWED_ORIGINS                               optional: a regular expression of allowed frontend origins
#
# On Render (render.yaml) the same image works unchanged: the service binds the port in PORT, 10000 there, 8080 elsewhere.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
# The wrapper and build scripts first, so the Gradle distribution and dependencies stay cached until they change.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY src src
# Maven Central sometimes drops a connection mid-build. Gradle gets longer timeouts and more download attempts, the whole step is
# retried, and the cache mount keeps what was already downloaded, so every attempt (and a re-run of `docker build`) resumes.
RUN --mount=type=cache,target=/root/.gradle \
    for attempt in 1 2 3; do \
        ./gradlew --no-daemon --console=plain \
            -Dorg.gradle.internal.http.connectionTimeout=60000 \
            -Dorg.gradle.internal.http.socketTimeout=120000 \
            -Dorg.gradle.internal.repository.max.tentatives=5 \
            buildLayers && break; \
        [ "$attempt" = 3 ] && exit 1; \
        echo "Gradle failed (attempt $attempt of 3), retrying"; sleep 10; \
    done

FROM eclipse-temurin:25-jre
WORKDIR /home/app
# Nothing in the service needs root: run as an unprivileged user. The ids are numeric because `COPY --link` cannot resolve a
# user name in --chown.
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app
COPY --link --chown=10001:10001 --from=build /workspace/build/docker/main/layers/libs /home/app/libs
COPY --link --chown=10001:10001 --from=build /workspace/build/docker/main/layers/app /home/app/
COPY --link --chown=10001:10001 --from=build /workspace/build/docker/main/layers/resources /home/app/resources
USER 10001:10001
# Size the heap from the container's memory limit rather than the JVM's small default share of it.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0" \
    MICRONAUT_ENVIRONMENTS=cloud
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/home/app/application.jar"]
