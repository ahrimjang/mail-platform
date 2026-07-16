package io.github.ahrimjang.mail.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ListMembershipJpaRepository extends JpaRepository<ListMembershipEntity, Long> {

    java.util.List<ListMembershipEntity> findByContactIdIn(java.util.Collection<Long> contactIds);


    boolean existsByListIdAndContactId(Long listId, Long contactId);

    List<ListMembershipEntity> findByContactIdOrderByListId(Long contactId);

    long countByListId(Long listId);

    void deleteByListIdAndContactId(Long listId, Long contactId);

    void deleteByListId(Long listId);

    void deleteByContactId(Long contactId);
}
