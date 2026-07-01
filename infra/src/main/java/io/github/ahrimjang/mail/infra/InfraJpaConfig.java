package io.github.ahrimjang.mail.infra;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Points entity scanning and Spring Data repository discovery at the infra
 * package. Picked up by each runnable app's component scan, so the API,
 * worker, and admin services all share the same persistence wiring.
 */
@Configuration
@EntityScan(basePackages = "io.github.ahrimjang.mail.infra")
@EnableJpaRepositories(basePackages = "io.github.ahrimjang.mail.infra")
public class InfraJpaConfig {
}
