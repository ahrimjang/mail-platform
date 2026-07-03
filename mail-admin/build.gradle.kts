plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":mail-core"))
    implementation(project(":infra"))

    implementation("org.springframework.boot:spring-boot-starter-web")

    // JSON file logging for OpenSearch ingestion via Fluent Bit (see logback-spring.xml).
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")
}
