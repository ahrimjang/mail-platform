package io.github.ahrimjang.mail.core.domain;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Domain model of an addressable contact. Deliberately has no status field —
 * the suppression list remains the single do-not-send source of truth.
 * Pure POJO — no JPA / framework concerns.
 */
public class Contact {

    private Long id;
    private Long workspaceId; // owning tenant
    private String email;
    private String firstName;
    private String lastName;
    private Map<String, String> attributes;
    private Instant createdAt;
    // Consent provenance: how the address entered the system (MANUAL/CSV_IMPORT)
    // and when. Null on rows collected before consent tracking — "no record".
    private String consentSource;
    private Instant consentedAt;

    public Contact() {
    }

    /** Factory for a freshly captured contact, before persistence. */
    public static Contact of(String email, String firstName, String lastName, Map<String, String> attributes) {
        Contact c = new Contact();
        c.email = email;
        c.firstName = firstName;
        c.lastName = lastName;
        c.attributes = attributes == null ? new HashMap<>() : attributes;
        c.createdAt = Instant.now();
        return c;
    }

    /**
     * Flatten this contact into the variable map used for template rendering:
     * custom attributes plus the well-known {@code email}/{@code firstName}/{@code lastName} keys.
     */
    public Map<String, String> toVariables() {
        Map<String, String> vars = new HashMap<>(attributes == null ? Map.of() : attributes);
        vars.put("email", email);
        if (firstName != null) {
            vars.put("firstName", firstName);
        }
        if (lastName != null) {
            vars.put("lastName", lastName);
        }
        // {{name}} — 에디터 기본 문구·플레이스홀더가 전부 이 변수를 권하는데 채워주는 곳이
        // 없어 "안녕하세요 님"으로 나가고 있었다. 성+이름(한국식 순서), 없으면 한쪽만,
        // 그것도 없으면 이메일 아이디 — 어떤 경우에도 빈칸으로 보내지 않는다.
        vars.put("name", displayName(email, firstName, lastName));
        return vars;
    }

    /** {{name}} 에 쓸 표시 이름 — 직접 입력 수신자(연락처 없음)에도 같은 규칙을 쓴다. */
    public static String displayName(String email, String firstName, String lastName) {
        String last = lastName == null ? "" : lastName.trim();
        String first = firstName == null ? "" : firstName.trim();
        if (!last.isEmpty() || !first.isEmpty()) {
            return last + first;
        }
        if (email == null) {
            return "";
        }
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    public Long getId() {
        return id;
    }

    public String getConsentSource() {
        return consentSource;
    }

    public void setConsentSource(String consentSource) {
        this.consentSource = consentSource;
    }

    public Instant getConsentedAt() {
        return consentedAt;
    }

    public void setConsentedAt(Instant consentedAt) {
        this.consentedAt = consentedAt;
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(Long workspaceId) {
        this.workspaceId = workspaceId;
    }


    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, String> attributes) {
        this.attributes = attributes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
