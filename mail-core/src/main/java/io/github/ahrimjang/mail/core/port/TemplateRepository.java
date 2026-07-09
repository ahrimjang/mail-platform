package io.github.ahrimjang.mail.core.port;

import io.github.ahrimjang.mail.core.domain.Template;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port for mail templates. Implemented by an infra adapter.
 */
public interface TemplateRepository {

    Template save(Template template);

    Optional<Template> findById(Long id);

    List<Template> findAll();

    void deleteById(Long id);

    /** Look up a built-in template by its seed key (used by the boot seeder and reset). */
    Optional<Template> findByBuiltinKey(String builtinKey);
}
