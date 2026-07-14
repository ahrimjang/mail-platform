package io.github.ahrimjang.mail.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ListUnsubscribeJpaRepository extends JpaRepository<ListUnsubscribeEntity, Long> {

    boolean existsByListIdAndContactId(Long listId, Long contactId);

    @Query("select u.listId from ListUnsubscribeEntity u where u.contactId = :contactId order by u.listId")
    List<Long> findListIdsByContactId(@Param("contactId") Long contactId);

    @Modifying
    @Transactional
    int deleteByListIdAndContactId(Long listId, Long contactId);
}
