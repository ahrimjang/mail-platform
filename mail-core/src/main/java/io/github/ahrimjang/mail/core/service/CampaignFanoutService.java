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
 * 리스트 캠페인의 수신자를 발송 큐로 펼친다 — 비동기로, 페이지 단위로. 워커의 팬아웃
 * 리스너가 캠페인당 한 번 호출한다.
 *
 * <p>캠페인 등록에서 미뤄 둔 절반이다: {@code CampaignService.create()} 는 캠페인만 저장하고
 * 팬아웃 잡 1건을 발행해 API 가 O(1) 로 반환하고, 무거운 N 행 확장은 요청 경로 밖인 여기서 한다.
 *
 * <p>원자적 QUEUED-&gt;EXPANDING claim 으로 멱등하다: RabbitMQ 는 at-least-once 라 같은 팬아웃
 * 잡이 다시 와도 claim 에서 밀려 건너뛴다 — 중복 메시지가 생기지 않는다. 펼치는 내내 캠페인은
 * EXPANDING 에 머무는데, 그래야 먼저 나간 메시지 때문에 발송 쪽이 캠페인을 미리 완료 처리하지
 * 않는다(뒤쪽 수신자 행은 아직 만들어지지도 않았다). 다 펼치면 SENDING 으로 넘기고, 그 사이
 * 이미 전부 빠져나갔으면 여기서 완료시킨다.
 */
@Service
public class CampaignFanoutService {

    private static final Logger log = LoggerFactory.getLogger(CampaignFanoutService.class);

    /** DB 왕복 한 번에 펼치는 수신자 수. 100만 행 리스트에서도 메모리 사용량을 일정하게 묶어 둔다. */
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
     * 리스트 캠페인 하나를 펼친다. 여러 번 호출돼도(재전달) 안전하다 — QUEUED-&gt;EXPANDING
     * claim 을 이긴 호출만 실제로 일한다.
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
        // 참여도 세그먼트: 작성 시점이 아니라 여기서 평가한다 — 예약 캠페인은 릴리스 시점의
        // 참여율로 걸러야 맞다. 팬아웃 한 번당 한 번만 로드한다.
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
            // 승자 플로우는 테스트 묶음만 큐에 넣는다 — 유보된 행(variant 없음)은 승자가
            // 정해질 때까지 PENDING 으로 남는다. 억제로 이미 종료된 행은 큐에 안 넣는다.
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
        // 승자 플로우: 테스트 묶음이 방금 나갔다 — 승자 스케줄러가 언제 판정하고 유보분을
        // 풀어야 하는지 그 시각을 찍어 둔다.
        if (campaign.hasWinnerFlow()) {
            campaigns.scheduleAbEvaluation(campaignId,
                    Instant.now().plus(Duration.ofMinutes(campaign.getAbEvalWaitMinutes())));
        }
        // SENDING 으로 넘기기 전에 이미 전부 빠져나간 경우(빠른 발송·빈 리스트)는 여기서
        // 끝낸다 — 전체 count 가 아니라 값싼 EXISTS 로 본다.
        if (!messages.hasPendingOrSending(campaignId)) {
            if (campaigns.completeIfSending(campaignId)) {
                notifications.campaignCompleted(campaign);   // claim 승자만 도달 — 1회 발행
            }
        }
        log.info("fanned out campaign {} into {} messages", campaignId, total);
    }

    /**
     * 팬아웃 한 번에서 쓰는 연락처별 참여도 판정식. 비율은 SENT 배달 대비 오픈·클릭한 메시지
     * 수(중복 제외)다. 배달 이력이 없는 연락처는 비율 자체가 없어 하한이 걸려 있으면 제외한다
     * — 참여도 세그먼트는 "읽는 것이 확인된 사람"을 뜻하고, 갓 들어온 연락처는 아직 그럴 수 없다.
     */
    private record EngagementFilter(Integer minOpenPercent, Integer minClickPercent,
                                    Map<Long, Long> sentByContact,
                                    Map<Long, EmailEventRepository.ContactEngagement> engagedByContact) {

        static EngagementFilter of(Campaign campaign, MailMessageRepository messages, EmailEventRepository events) {
            if (!campaign.hasEngagementSegment()) {
                return new EngagementFilter(null, null, Map.of(), Map.of());
            }
            // 이 워크스페이스·최근 창(ContactEngagementService.WINDOW)으로 한정 — 콘솔의
            // 세그먼트 미리보기와 같은 창을 써야 "예상 N명"과 실제 대상이 어긋나지 않는다
            java.time.Instant since = ContactEngagementService.windowStart();
            return new EngagementFilter(
                    campaign.getSegMinOpenPercent(),
                    campaign.getSegMinClickPercent(),
                    messages.countSentByContact(campaign.getWorkspaceId(), since).stream()
                            .collect(Collectors.toMap(
                                    MailMessageRepository.ContactSentCount::contactId,
                                    MailMessageRepository.ContactSentCount::sent)),
                    events.countEngagementByContact(campaign.getWorkspaceId(), since).stream()
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
