package io.github.ahrimjang.mail.infra.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignJpaRepository extends JpaRepository<CampaignEntity, Long> {
}
