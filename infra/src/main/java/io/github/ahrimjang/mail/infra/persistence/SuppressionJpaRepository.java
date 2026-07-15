package io.github.ahrimjang.mail.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface SuppressionJpaRepository extends JpaRepository<SuppressionEntity, Long> {

    boolean existsByEmail(String email);

    Optional<SuppressionEntity> findByEmail(String email);

    void deleteByEmail(String email);

    @Query("select s.reason, count(s) from SuppressionEntity s group by s.reason order by count(s) desc")
    java.util.List<Object[]> countByReason();

    @Query("select s.reason, count(s) from SuppressionEntity s where s.createdAt >= ?1 "
            + "group by s.reason order by count(s) desc")
    java.util.List<Object[]> countByReasonSince(java.time.Instant since);
}
