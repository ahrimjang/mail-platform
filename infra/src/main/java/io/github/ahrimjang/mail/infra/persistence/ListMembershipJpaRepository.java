package io.github.ahrimjang.mail.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ListMembershipJpaRepository extends JpaRepository<ListMembershipEntity, Long> {

    boolean existsByListIdAndContactId(Long listId, Long contactId);

    long countByListId(Long listId);

    void deleteByListIdAndContactId(Long listId, Long contactId);

    void deleteByListId(Long listId);

    void deleteByContactId(Long contactId);
}
