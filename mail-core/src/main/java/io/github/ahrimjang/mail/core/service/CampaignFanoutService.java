package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.core.domain.Campaign;
import io.github.ahrimjang.mail.core.domain.Contact;
import io.github.ahrimjang.mail.core.domain.MailMessage;
import io.github.ahrimjang.mail.core.port.CampaignRepository;
import io.github.ahrimjang.mail.core.port.ContactRepository;
import io.github.ahrimjang.mail.core.port.EmailEventRepository;
import io.github.ahrimjang.mail.core.port.MailMessageRepository;
import io.github.ahrimjang.mail.core.port.MailQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Expands a list campaign's recipients into the send queue, asynchronously and in
 * batches. Invoked by the worker's fan-out listener, one call per campaign.
 *
 * <p>This is the deferred half of campaign creation: {@code CampaignService.create()}
 * only persists the campaign and publishes a fan-out job, so the API returns in O(1);
 * the heavy N-row expansion happens here off the request path.
 *
 * <p>Idempotent via an atomic QUEUED-&gt;EXPANDING claim: RabbitMQ is at-least-once, so a
 * redelivered fan-out job loses the claim and is skipped instead of creating duplicate
 * messages. The campaign stays EXPANDING for the whole expansion so dispatch never
 * completes it early (its later messages aren't created yet); fan-out flips it to
 * SENDING when done and completes it if everything already drained.
 */
@Service
public class CampaignFanoutService {

    private static final Logger log = LoggerFactory.getLogger(CampaignFanoutService.class);

    /** Recipients expanded per DB round-trip. Keeps memory bounded on million-row lists. */
    private static final int PAGE = 1000;

    private final CampaignRepository campaigns;
    private final MailMessageRepository messages;
    private final ContactRepository contacts;
    private final EmailEventRepository events;
    private final MailQueue mailQueue;
    private final NotificationService notifications;
    private final io.github.ahrimjang.mail.core.port.SuppressionRepository suppressions;

    public CampaignFanoutService(CampaignRepository campaigns, MailMessageRepository messages,
                                 ContactRepository contacts, EmailEventRepository events, MailQueue mailQueue,
                                 NotificationService notifications,
                                 io.github.ahrimjang.mail.core.port.SuppressionRepository suppressions) {
        this.campaigns = campaigns;
        this.messages = messages;
        this.contacts = contacts;
        this.events = events;
        this.mailQueue = mailQueue;
        this.suppressions = suppressions;
        this.notifications = notifications;
    }

    /**
     * Expand one list campaign. Safe to call more than once (redelivery): only the
     * caller that wins the QUEUED-&gt;EXPANDING claim does the work.
     */
    public void expand(Long campaignId) {
        if (!campaigns.claimForFanout(campaignId)) {
            log.debug("skip fan-out: campaign {} already claimed/expanded by another consumer", campaignId);
            return;
        }
        Campaign campaign = campaigns.findById(campaignId).orElse(null);
        if (campaign == null || campaign.getListId() == null) {
            return;
        }
        Long listId = campaign.getListId();
        // Engagement segment: evaluated here (not at authoring) so a scheduled
        // campaign filters on rates as of the release. Loaded once per fan-out.
        EngagementFilter segment = EngagementFilter.of(campaign, messages, events);

        // 재개 커서: 팬아웃 도중 죽었다가 스위퍼가 되돌린 캠페인은 이미 만든 메시지 뒤부터
        // 잇는다. 연락처는 id 오름차순으로 페이지를 넘기므로 "가장 큰 contactId"가 곧
        // 마지막으로 처리한 위치다. 처음 도는 캠페인은 메시지가 없어 0 에서 시작한다.
        Long resumeFrom = messages.maxContactIdByCampaign(campaignId);
        long afterId = resumeFrom == null ? 0L : resumeFrom;
        if (afterId > 0) {
            log.warn("fan-out of campaign {} resumes after contactId {}", campaignId, afterId);
        }
        long total = 0;
        while (true) {
            // 발송 중 중단(ARCH-8): 페이지마다 상태를 본다. 100만 명 리스트를 펼치는 도중에
            // 중단이 들어오면 여기서 멈춰야 나머지 수신자 행이 만들어지지 않는다.
            if (campaigns.findById(campaignId).map(Campaign::getStatus)
                    .filter(s -> s == io.github.ahrimjang.mail.common.CampaignStatus.CANCELED).isPresent()) {
                log.warn("fan-out of campaign {} stopped: campaign was aborted ({} messages created so far)",
                        campaignId, total);
                return;
            }
            List<Contact> page = contacts.findSubscribedByListIdAfter(listId, afterId, PAGE);
            if (page.isEmpty()) {
                break;
            }
            List<Contact> targets = page.stream().filter(segment::test).toList();
            // 억제 주소는 여기서 걸러 SUPPRESSED 로 바로 기록한다(ARCH-10). 큐에 넣었다가
            // dispatch 에서 빼면 잡 1건·토큰 1개·DB 왕복이 전부 낭비다 — 억제 30% 명단이면
            // 발송 예산 30% 가 허비된다. 행은 남겨서 "발송 제외 N명" 통계는 그대로 보이게 한다.
            java.util.Set<String> suppressed = new java.util.HashSet<>(suppressions.findSuppressedEmails(
                    campaign.getWorkspaceId(), targets.stream().map(Contact::getEmail).toList()));
            List<MailMessage> batch = targets.stream()
                    .map(c -> {
                        MailMessage m = MailMessage.queued(campaignId, c.getEmail(), c.getId());
                        if (suppressed.contains(c.getEmail())) {
                            m.markSuppressed();
                            return m;
                        }
                        if (campaign.isAbTest()) {
                            m.setVariant(campaign.hasWinnerFlow()
                                    ? AbVariantAssigner.assignWithHoldout(c.getEmail(),
                                            campaign.getAbTestPercent(), campaign.getAbSplitPercent())
                                    : AbVariantAssigner.assign(c.getEmail(), campaign.getAbSplitPercent()));
                        }
                        return m;
                    })
                    .toList();
            List<MailMessage> saved = batch.isEmpty() ? List.of() : messages.saveAll(batch);
            // Winner flow only enqueues the test batch: held rows (variant null)
            // stay PENDING until the winner is decided. 억제로 이미 종료된 행은 큐에 안 넣는다.
            saved.stream()
                    .filter(m -> m.getStatus() == io.github.ahrimjang.mail.common.MessageStatus.PENDING)
                    .filter(m -> !campaign.hasWinnerFlow() || m.getVariant() != null)
                    .forEach(m -> mailQueue.enqueue(m.getId()));
            total += saved.size();
            afterId = page.get(page.size() - 1).getId();
            if (page.size() < PAGE) {
                break;
            }
        }

        campaigns.markExpanded(campaignId); // EXPANDING -> SENDING
        // Winner flow: the test batch just went out — stamp when the winner scheduler
        // should evaluate it and release the held-out remainder.
        if (campaign.hasWinnerFlow()) {
            campaigns.scheduleAbEvaluation(campaignId,
                    Instant.now().plus(Duration.ofMinutes(campaign.getAbEvalWaitMinutes())));
        }
        // If every message already drained before we flipped to SENDING (fast sends /
        // empty list), finish it here — cheap EXISTS, not a full count.
        if (!messages.hasPendingOrSending(campaignId)) {
            if (campaigns.completeIfSending(campaignId)) {
                notifications.campaignCompleted(campaign);   // claim 승자만 도달 — 1회 발행
            }
        }
        log.info("fanned out campaign {} into {} messages", campaignId, total);
    }

    /**
     * Per-contact engagement predicate of one fan-out run. Rates are distinct
     * opened/clicked messages over SENT deliveries; contacts with no delivery
     * history have no rate and are excluded when a floor is set (an engagement
     * segment means "proven readers", which a fresh member cannot be yet).
     */
    private record EngagementFilter(Integer minOpenPercent, Integer minClickPercent,
                                    Map<Long, Long> sentByContact,
                                    Map<Long, EmailEventRepository.ContactEngagement> engagedByContact) {

        static EngagementFilter of(Campaign campaign, MailMessageRepository messages, EmailEventRepository events) {
            if (!campaign.hasEngagementSegment()) {
                return new EngagementFilter(null, null, Map.of(), Map.of());
            }
            return new EngagementFilter(
                    campaign.getSegMinOpenPercent(),
                    campaign.getSegMinClickPercent(),
                    messages.countSentByContact().stream()
                            .collect(Collectors.toMap(
                                    MailMessageRepository.ContactSentCount::contactId,
                                    MailMessageRepository.ContactSentCount::sent)),
                    events.countEngagementByContact().stream()
                            .collect(Collectors.toMap(
                                    EmailEventRepository.ContactEngagement::contactId,
                                    Function.identity())));
        }

        boolean test(Contact contact) {
            if (minOpenPercent == null && minClickPercent == null) {
                return true;
            }
            long sent = sentByContact.getOrDefault(contact.getId(), 0L);
            if (sent == 0) {
                return false;
            }
            EmailEventRepository.ContactEngagement engaged = engagedByContact.get(contact.getId());
            long opened = engaged == null ? 0 : engaged.opened();
            long clicked = engaged == null ? 0 : engaged.clicked();
            return (minOpenPercent == null || percentOf(opened, sent) >= minOpenPercent)
                    && (minClickPercent == null || percentOf(clicked, sent) >= minClickPercent);
        }

        private static int percentOf(long part, long whole) {
            return whole == 0 ? 0 : (int) Math.round(part * 100.0 / whole);
        }
    }
}
