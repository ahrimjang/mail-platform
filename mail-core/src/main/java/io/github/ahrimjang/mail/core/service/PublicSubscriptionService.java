package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.common.SubscribeRequest;
import io.github.ahrimjang.mail.core.domain.Contact;
import io.github.ahrimjang.mail.core.domain.Workspace;
import io.github.ahrimjang.mail.core.port.ContactListRepository;
import io.github.ahrimjang.mail.core.port.ContactRepository;
import io.github.ahrimjang.mail.core.port.WorkspaceContext;
import io.github.ahrimjang.mail.core.port.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.NoSuchElementException;

/**
 * 외부 구독 신청 연동. 고객 사이트의 구독 폼이 우리 공개 API 로 연락처를 밀어넣는
 * 경로다 — 테넌트는 JWT 가 아니라 워크스페이스 API 키(X-Api-Key)로 역해석한다
 * (추적/웹훅과 같은 공개 경로 계보).
 *
 * <p>구독 신청은 그 자체가 수신 동의이므로 consentSource 를 "API" 로 기록한다.
 * 단, 과거에 수신거부(suppression)한 주소를 여기서 자동 복구하지는 않는다 —
 * 수신거부 해제는 수신자 본인의 별도 의사표시가 필요한 영역이라 조용히 둔다.
 */
@Service
public class PublicSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(PublicSubscriptionService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final WorkspaceRepository workspaces;
    private final ContactRepository contacts;
    private final ContactListRepository lists;
    private final PlanLimits planLimits;
    private final WorkspaceContext ctx;   // 콘솔용(키 발급/조회) — 공개 경로에서는 안 쓴다

    public PublicSubscriptionService(WorkspaceRepository workspaces, ContactRepository contacts,
                                     ContactListRepository lists, PlanLimits planLimits,
                                     WorkspaceContext ctx) {
        this.workspaces = workspaces;
        this.contacts = contacts;
        this.lists = lists;
        this.planLimits = planLimits;
        this.ctx = ctx;
    }

    /** 콘솔: 현재 워크스페이스의 API 키 (null = 미발급). ADMIN 전용. */
    public String currentApiKey() {
        requireAdmin();
        return workspaces.findById(ctx.currentWorkspaceId())
                .map(Workspace::getApiKey)
                .orElse(null);
    }

    /** 콘솔: API 키 발급/재발급 — 재발급하면 이전 키는 즉시 무효. ADMIN 전용. */
    public String issueApiKey() {
        requireAdmin();
        Workspace workspace = workspaces.findById(ctx.currentWorkspaceId())
                .orElseThrow(() -> new NoSuchElementException("workspace not found"));
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String key = "opk_" + HexFormat.of().formatHex(bytes);   // opk_ + 48 hex = 52자
        workspace.setApiKey(key);
        workspaces.save(workspace);
        log.info("구독 API 키 발급: workspace={}", workspace.getId());
        return key;
    }

    private void requireAdmin() {
        if (!ctx.isAdmin()) {
            throw new ForbiddenException("API 키는 관리자만 다룰 수 있어요.");
        }
    }

    /**
     * 공개 구독 신청 — 신규면 생성(플랜 연락처 한도 검사), 기존이면 그대로 두고
     * 리스트 가입만 보장한다(멱등). @return true = 신규 생성
     */
    public boolean subscribe(String apiKey, SubscribeRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new InvalidApiKeyException();
        }
        Workspace workspace = workspaces.findByApiKey(apiKey)
                .orElseThrow(InvalidApiKeyException::new);
        if (request.email() == null || !request.email().contains("@")) {
            throw new IllegalArgumentException("valid email is required");
        }
        Long workspaceId = workspace.getId();

        Contact contact = contacts.findByWorkspaceAndEmail(workspaceId, request.email()).orElse(null);
        boolean created = false;
        if (contact == null) {
            planLimits.assertContactsAddable(workspaceId, 1);
            Contact fresh = Contact.of(request.email(), request.firstName(), request.lastName(), null);
            fresh.setWorkspaceId(workspaceId);
            // 구독 신청 자체가 동의 — 출처를 API 로 남긴다
            fresh.setConsentSource("API");
            fresh.setConsentedAt(Instant.now());
            contact = contacts.save(fresh);
            created = true;
        }

        if (request.listId() != null) {
            // 남의 리스트는 존재를 숨긴다 (404 — V16 원칙)
            lists.findById(request.listId())
                    .filter(l -> workspaceId.equals(l.getWorkspaceId()))
                    .orElseThrow(() -> new NoSuchElementException("list not found: " + request.listId()));
            lists.addMember(request.listId(), contact.getId());
        }
        log.info("공개 구독 신청: workspace={} created={}", workspaceId, created);
        return created;
    }
}
