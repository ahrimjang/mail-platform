package io.github.ahrimjang.mail.infra.persistence;

import io.github.ahrimjang.mail.core.domain.User;
import io.github.ahrimjang.mail.core.port.UserRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Adapter: implements the core {@link UserRepository} port over Spring Data JPA,
 * mapping between the domain model and the persistence entity.
 */
@Repository
public class JpaUserRepository implements UserRepository {

    private final UserJpaRepository jpa;

    public JpaUserRepository(UserJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public User save(User user) {
        UserEntity saved = jpa.save(toEntity(user));
        return toDomain(saved);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return jpa.findByEmail(email).map(this::toDomain);
    }

    @Override
    public boolean existsByEmail(String email) {
        return jpa.existsByEmail(email);
    }

    private UserEntity toEntity(User u) {
        return new UserEntity(u.getId(), u.getEmail(), u.getPasswordHash(), u.getDisplayName(), u.getCreatedAt());
    }

    private User toDomain(UserEntity e) {
        User u = new User();
        u.setId(e.getId());
        u.setEmail(e.getEmail());
        u.setPasswordHash(e.getPasswordHash());
        u.setDisplayName(e.getDisplayName());
        u.setCreatedAt(e.getCreatedAt());
        return u;
    }
}
