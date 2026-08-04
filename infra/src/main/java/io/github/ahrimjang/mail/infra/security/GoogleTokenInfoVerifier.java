package io.github.ahrimjang.mail.infra.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ahrimjang.mail.core.port.GoogleIdentityVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 어댑터: 구글 tokeninfo 엔드포인트로 ID 토큰을 검증한다. 구글이 서명·만료를
 * 확인해주므로 우리는 aud(우리 클라이언트 대상인지)만 추가로 확인하면 된다.
 * 공개키 캐싱 라이브러리 없이 HTTP 한 번으로 끝나 의존성이 늘지 않는다
 * (토스 어댑터와 같은 구도) — 로그인 시점 1회라 왕복 지연도 문제없다.
 */
@Component
public class GoogleTokenInfoVerifier implements GoogleIdentityVerifier {

    private static final String TOKENINFO_URL = "https://oauth2.googleapis.com/tokeninfo?id_token=";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String clientId;

    public GoogleTokenInfoVerifier(@Value("${app.google.client-id:}") String clientId) {
        this.clientId = clientId;
    }

    @Override
    public GoogleIdentity verify(String idToken) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException("Google 로그인이 아직 설정되지 않았어요.");
        }
        if (idToken == null || idToken.isBlank()) {
            throw new IllegalArgumentException("유효하지 않은 Google 토큰입니다.");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TOKENINFO_URL + URLEncoder.encode(idToken, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                // 위조·만료 토큰은 구글이 400 으로 거른다
                throw new IllegalArgumentException("유효하지 않은 Google 토큰입니다.");
            }
            JsonNode body = MAPPER.readTree(response.body());
            if (!clientId.equals(body.path("aud").asText())) {
                // 다른 앱에 발급된 정상 토큰의 재사용(토큰 치환) 차단
                throw new IllegalArgumentException("이 서비스용 Google 토큰이 아닙니다.");
            }
            return new GoogleIdentity(
                    body.path("sub").asText(),
                    body.path("email").asText(),
                    body.path("name").asText(null),
                    "true".equals(body.path("email_verified").asText()));
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Google 로그인 확인 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.", e);
        }
    }
}
