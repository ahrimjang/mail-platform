package io.github.ahrimjang.mail.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ContactJpaRepository extends JpaRepository<ContactEntity, Long> {

    Optional<ContactEntity> findByEmail(String email);

    boolean existsByEmail(String email);

    @Query("select c from ContactEntity c, ListMembershipEntity m where m.listId = ?1 and m.contactId = c.id order by c.id")
    List<ContactEntity> findByListId(Long listId);
}
