plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":mail-core"))
    implementation(project(":infra"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("org.springframework.kafka:spring-kafka")

    // Metrics: the worker serves ONLY /actuator/** over HTTP (:8082) so Prometheus
    // can scrape it — it exposes no business endpoints (those live in mail-api).
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // JSON file logging for OpenSearch ingestion via Fluent Bit (see logback-spring.xml).
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")
}
