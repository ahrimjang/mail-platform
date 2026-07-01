plugins {
    `java-library`
}

dependencies {
    api(project(":mail-common"))

    // Domain services use DI annotations but stay free of web/persistence concerns.
    implementation("org.springframework:spring-context")
}
