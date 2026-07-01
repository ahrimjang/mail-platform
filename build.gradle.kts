plugins {
    id("org.springframework.boot") version "3.4.1" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
    group = "io.github.ahrimjang"
    version = "1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
        imports {
            mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
        }
    }

    dependencies {
        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    // Run every Spring Boot app from the repo root so the relative H2 path
    // (./data/maildb) resolves to ONE shared file — letting mail-api and
    // mail-worker share the same send queue during local POC runs.
    tasks.withType<org.springframework.boot.gradle.tasks.run.BootRun>().configureEach {
        workingDir = rootProject.projectDir
    }
}
