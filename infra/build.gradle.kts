plugins {
    `java-library`
}

dependencies {
    api(project(":mail-core"))

    // Adapters implementing the ports declared in mail-core.
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("org.springframework.kafka:spring-kafka")

    // Schema migrations (db/migration/V*.sql) — replaces ddl-auto: update.
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    // Contact attribute map <-> JSON string column serialization.
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // Domain counters/timers in adapters (SMTP sends, throttle decisions). Recorded
    // against the global registry — apps with actuator attach a real registry, apps
    // without one make these no-ops.
    implementation("io.micrometer:micrometer-core")

    // Auth adapters: password hashing + JWT.
    implementation("org.springframework.security:spring-security-crypto")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    runtimeOnly("org.postgresql:postgresql")

    // In-memory DB for tests only (no Postgres dependency in test runs).
    testRuntimeOnly("com.h2database:h2")
}
