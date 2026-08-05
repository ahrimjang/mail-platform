package io.github.ahrimjang.mail.infra.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface NotificationJpaRepository extends JpaRepository<NotificationEntity, Long> {

    @Query("select n from NotificationEntity n where n.workspaceId = :workspaceId order by n.createdAt desc")
    List<NotificationEntity> findRecent(@Param("workspaceId") Long workspaceId, Pageable pageable);

    long countByWorkspaceIdAndReadAtIsNull(Long workspaceId);

    @Modifying
    @Query("update NotificationEntity n set n.readAt = :now where n.workspaceId = :workspaceId and n.readAt is null")
    int markAllRead(@Param("workspaceId") Long workspaceId, @Param("now") Instant now);
}
