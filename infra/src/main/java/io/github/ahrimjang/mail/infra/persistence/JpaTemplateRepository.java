package io.github.ahrimjang.mail.infra.persistence;

import io.github.ahrimjang.mail.core.domain.Template;
import io.github.ahrimjang.mail.core.port.TemplateRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Adapter: implements the core {@link TemplateRepository} port over Spring Data JPA,
 * mapping between the domain model and the persistence entity.
 */
@Repository
public class JpaTemplateRepository implements TemplateRepository {

    private final TemplateJpaRepository jpa;

    public JpaTemplateRepository(TemplateJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Template save(Template template) {
        TemplateEntity saved = jpa.save(toEntity(template));
        return toDomain(saved);
    }

    @Override
    public Optional<Template> findById(Long id) {
        return jpa.findById(id).map(this::toDomain);
    }

    @Override
    public List<Template> findAll() {
        return jpa.findAll().stream().map(this::toDomain).toList();
    }

    @Override
    public void deleteById(Long id) {
        jpa.deleteById(id);
    }

    private TemplateEntity toEntity(Template t) {
        return new TemplateEntity(t.getId(), t.getName(), t.getSubject(), t.getHtmlBody(),
                t.getCreatedAt(), t.getUpdatedAt());
    }

    private Template toDomain(TemplateEntity e) {
        Template t = new Template();
        t.setId(e.getId());
        t.setName(e.getName());
        t.setSubject(e.getSubject());
        t.setHtmlBody(e.getHtmlBody());
        t.setCreatedAt(e.getCreatedAt());
        t.setUpdatedAt(e.getUpdatedAt());
        return t;
    }
}
