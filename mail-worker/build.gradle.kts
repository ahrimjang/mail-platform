plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":mail-core"))
    implementation(project(":infra"))

    implementation("org.springframework.boot:spring-boot-starter")
}
