package io.github.ahrimjang.mail.api;

import io.github.ahrimjang.mail.core.service.TemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Inserts the built-in example templates at boot if they are missing.
 * Idempotent: existing rows (including user-edited built-ins) are untouched,
 * so this only does work on first boot or when a release adds new seeds.
 */
@Component
public class BuiltinTemplateSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BuiltinTemplateSeeder.class);

    private final TemplateService templates;

    public BuiltinTemplateSeeder(TemplateService templates) {
        this.templates = templates;
    }

    @Override
    public void run(ApplicationArguments args) {
        int inserted = templates.seedBuiltins();
        if (inserted > 0) {
            log.info("seeded {} built-in template(s)", inserted);
        }
    }
}
