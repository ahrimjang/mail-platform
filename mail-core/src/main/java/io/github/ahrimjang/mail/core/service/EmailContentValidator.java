package io.github.ahrimjang.mail.core.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 이메일 저장 시점의 본문 검사. 발송 시점이 아니라 <b>저장 시점</b>에 보는 이유는, 문제를
 * 고칠 수 있는 사람이 화면 앞에 있는 순간이 그때뿐이기 때문이다 — 발송 시점에 걸러도
 * 캠페인은 이미 등록됐고, 수신자는 이미 기다리고 있다.
 *
 * <p>두 단계로 나눈다.
 * <ul>
 *   <li><b>거부</b>: 메일 클라이언트가 어차피 지우는 데다 스팸 필터가 감점하는 태그
 *       ({@code <script>}·{@code <iframe>}·{@code <form>}). 살려둘 이유가 없어 저장을 막는다.</li>
 *   <li><b>경고</b>: 보내지긴 하는데 의도와 다르게 보일 것들(미지원 변수, 크기 초과).
 *       사람이 일부러 그랬을 수도 있으므로 막지 않고 알려만 준다.</li>
 * </ul>
 *
 * <p>MCP 로 외부(사내 AI 포탈)에서 본문이 들어와도 같은 검사를 통과한다 — 이 검사는
 * 컨트롤러가 아니라 저장 서비스가 부르기 때문이다.
 */
@Component
public class EmailContentValidator {

    /** {@link TemplateRenderer} 와 같은 문법이어야 한다 — 다르면 경고가 거짓말이 된다. */
    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*\\}\\}");

    /**
     * 변수처럼 생겼지만 렌더러 문법에 안 맞는 것 — {@code {{고객명}}}, {{'{{'}}주문 번호}} 처럼
     * 한글·공백이 섞인 경우. 치환 대상이 아니라 <b>중괄호째 그대로 발송된다</b>(빈칸보다 나쁘다).
     */
    private static final Pattern BRACED = Pattern.compile("\\{\\{([^{}]{1,80}?)\\}\\}");

    /** 발송 시 항상 채워지는 변수. 이 밖의 이름은 연락처 속성에서 찾고, 없으면 빈칸이 된다. */
    private static final Set<String> BUILT_IN_VARS = Set.of("email", "name", "firstName", "lastName");

    /**
     * Gmail 이 약 102KB 에서 본문을 잘라내고 "전체 메시지 보기" 링크로 대체한다. 잘린 뒤쪽에
     * 수신거부 링크가 있으면 사실상 없는 것과 같아진다.
     */
    static final int SIZE_WARN_BYTES = 102 * 1024;

    /** 메일에서 동작하지 않고 스팸 판정에 불리한 태그. */
    private static final Pattern BLOCKED_TAG = Pattern.compile("<\\s*(script|iframe|form)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * 저장 전 검사.
     *
     * @return 사람에게 보여줄 경고 문구들 (비어 있으면 문제 없음)
     * @throws IllegalArgumentException 거부 대상 태그가 있을 때
     */
    public List<String> validate(String subject, String htmlBody) {
        String blocked = firstBlockedTag(htmlBody);
        if (blocked != null) {
            throw new IllegalArgumentException(
                    "메일 본문에는 <" + blocked + "> 태그를 넣을 수 없어요. "
                            + "메일 클라이언트가 지우거나 스팸으로 분류할 수 있어서 저장하지 않았어요.");
        }

        List<String> warnings = new ArrayList<>();
        Set<String> unknown = unknownVariables(subject, htmlBody);
        if (!unknown.isEmpty()) {
            warnings.add("연락처 정보로 채울 수 없는 변수가 있어요: " + join(unknown)
                    + " — 연락처 속성에 같은 이름이 없으면 빈칸으로 나갑니다. "
                    + "기본 제공: {{name}}, {{email}}, {{firstName}}, {{lastName}}");
        }
        Set<String> malformed = malformedVariables(subject, htmlBody);
        if (!malformed.isEmpty()) {
            warnings.add("변수로 인식되지 않는 표기가 있어요: " + join(malformed)
                    + " — 중괄호까지 그대로 발송됩니다. 변수 이름에는 영문·숫자·밑줄만 쓸 수 있어요"
                    + "(예: {{고객명}} 대신 {{name}}).");
        }
        int bytes = htmlBody == null ? 0 : htmlBody.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > SIZE_WARN_BYTES) {
            warnings.add(String.format(
                    "본문이 %,dKB 라 Gmail 에서 잘릴 수 있어요(약 102KB 기준). "
                            + "잘리면 아래쪽 수신거부 링크가 보이지 않습니다 — 이미지를 줄이거나 내용을 나눠주세요.",
                    bytes / 1024));
        }
        return warnings;
    }

    /** 거부 대상 태그 이름 — 없으면 null. */
    private static String firstBlockedTag(String html) {
        if (html == null) {
            return null;
        }
        Matcher m = BLOCKED_TAG.matcher(html);
        return m.find() ? m.group(1).toLowerCase() : null;
    }

    private static String join(Set<String> names) {
        return names.stream().map(v -> "{{" + v + "}}").reduce((a, b) -> a + ", " + b).orElse("");
    }

    /** 제목·본문에 쓰였지만 기본 제공이 아닌 변수 이름 — 등장 순서를 유지한다. */
    private static Set<String> unknownVariables(String subject, String htmlBody) {
        Set<String> found = new LinkedHashSet<>();
        collect(VAR, subject, found);
        collect(VAR, htmlBody, found);
        found.removeAll(BUILT_IN_VARS);
        return found;
    }

    /** 중괄호로 감쌌지만 렌더러가 변수로 인식하지 못하는 표기 — 그대로 발송된다. */
    private static Set<String> malformedVariables(String subject, String htmlBody) {
        Set<String> braced = new LinkedHashSet<>();
        collect(BRACED, subject, braced);
        collect(BRACED, htmlBody, braced);
        // 렌더러 문법에 맞는 것은 제외 — 남는 것이 "치환 안 되는" 표기다
        braced.removeIf(inner -> VAR.matcher("{{" + inner + "}}").matches());
        return braced;
    }

    private static void collect(Pattern pattern, String text, Set<String> into) {
        if (text == null) {
            return;
        }
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            into.add(m.group(1));
        }
    }
}
