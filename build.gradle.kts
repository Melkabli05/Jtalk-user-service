plugins {
    id("io.micronaut.application") version "5.0.2"
    id("com.gradleup.shadow") version "9.4.1"
    id("io.micronaut.test-resources") version "5.0.2"
    id("io.micronaut.aot") version "5.0.2"
}

version = "0.1"
group = "com.jtalk.userservice"



repositories {
    mavenCentral()
}

val mapstructVersion = "1.6.3"

dependencies {
    // Order matters: Lombok must run first, then MapStruct (with its Lombok bridge), then Micronaut's processors.
    annotationProcessor("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok-mapstruct-binding:0.2.0")
    annotationProcessor("org.mapstruct:mapstruct-processor:$mapstructVersion")
    implementation("org.mapstruct:mapstruct:$mapstructVersion")
    annotationProcessor("io.micronaut.data:micronaut-data-processor")
    annotationProcessor("io.micronaut:micronaut-http-validation")
    annotationProcessor("io.micronaut.security:micronaut-security-processor")
    annotationProcessor("io.micronaut.serde:micronaut-serde-processor")
    annotationProcessor("io.micronaut.validation:micronaut-validation-processor")
    implementation("io.micronaut:micronaut-management")
    implementation("io.micronaut.data:micronaut-data-jdbc")
    implementation("io.micronaut.flyway:micronaut-flyway")
    implementation("io.micronaut.problem:micronaut-problem-json")
    implementation("io.micronaut.security:micronaut-security")
    implementation("io.micronaut.security:micronaut-security-jwt")
    implementation("io.micronaut.serde:micronaut-serde-jackson")
    implementation("io.micronaut.sql:micronaut-jdbc-hikari")
    // Redis: the profile cache and the follow graph (the database stays the source of truth)
    implementation("io.micronaut.redis:micronaut-redis-lettuce")
    implementation("io.micronaut.validation:micronaut-validation")
    implementation("jakarta.validation:jakarta.validation-api")
    compileOnly("io.micronaut:micronaut-http-client")
    compileOnly("org.projectlombok:lombok")
    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.yaml:snakeyaml")
    testImplementation("io.micronaut:micronaut-http-client")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.1")
    testImplementation("com.auth0:java-jwt:4.6.0")   // signs the bearer tokens the tests send
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    aotPlugins(platform("io.micronaut.platform:micronaut-platform:5.2.1"))
    aotPlugins("io.micronaut.security:micronaut-security-aot")
}



application {
    mainClass = "com.jtalk.userservice.Application"
}

java {
    sourceCompatibility = JavaVersion.toVersion("25")
    targetCompatibility = JavaVersion.toVersion("25")
}




graalvmNative.toolchainDetection = false
graalvmNative {
    binaries {
        all {
            buildArgs.add("-H:+SharedArenaSupport")
        }
    }
}




micronaut {
    runtime("netty")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("com.jtalk.userservice.*")
    }
    testResources {
        version = "4.0.0"
    }
    aot {
        // Please review carefully the optimizations enabled below
        // Check https://micronaut-projects.github.io/micronaut-aot/latest/guide/ for more details
        convertYamlToJava = false
        precomputeOperations = false
        cacheEnvironment = false
        optimizeClassLoading = true
        deduceEnvironment = false
        optimizeNetty = true
        replaceLogbackXml = true
        configurationProperties.put("micronaut.security.jwks.enabled","false")
    }

}
tasks.withType<io.micronaut.gradle.testresources.StartTestResourcesService>().configureEach {
      useClassDataSharing.set(false)
}

// Docker: the Dockerfile at the project root is the source of truth (it began as the output of `./gradlew dockerfile` and builds
// the layered jar itself), so `docker build .` builds the image. With a root Dockerfile the plugin no longer configures its own
// `dockerfile` task, which is why that task is not tuned here.







// https://docs.gradle.org/current/userguide/upgrading_major_version_9.html#test_task_fails_when_no_tests_are_discovered
tasks.withType<AbstractTestTask>().configureEach {
    failOnNoDiscoveredTests = false
}




