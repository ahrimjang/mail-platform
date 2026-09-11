package io.github.ahrimjang.mail.infra.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PlatformAuditJpaRepository extends JpaRepository<PlatformAuditEntity, Long> {

    @Query("select a from PlatformAuditEntity a order by a.createdAt desc")
    List<PlatformAuditEntity> findRecent(Pageable pageable);
}
