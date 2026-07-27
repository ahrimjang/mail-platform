package io.github.ahrimjang.mail.infra.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ahrimjang.mail.core.port.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * 어댑터: 토스페이먼츠 빌링(정기결제) REST 구현.
 * 인증은 시크릿 키의 Basic 헤더(키 뒤에 콜론) — 시크릿 키는 서버에만 존재한다.
 * 개발계는 토스 문서의 공용 테스트 키가 기본값(실결제 없음, 아무 카드나 승인됨).
 */
@Component
public class TossPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(TossPaymentGateway.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String basicAuth;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public TossPaymentGateway(
            @Value("${app.toss.secret-key:test_sk_docs_OaPz8L5KdmQXkzRz3y47BMw6}") String secretKey,
            @Value("${app.toss.base-url:https://api.tosspayments.com}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.basicAuth = "Basic " + Base64.getEncoder()
                .encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String issueBillingKey(String customerKey, String authKey) {
        JsonNode res = post("/v1/billing/authorizations/issue",
                Map.of("authKey", authKey, "customerKey", customerKey));
        return res.path("billingKey").asText();
    }

    @Override
    public String chargeBilling(String billingKey, String customerKey, int amountKrw,
                                String orderId, String orderName) {
        JsonNode res = post("/v1/billing/" + billingKey,
                Map.of("customerKey", customerKey, "amount", amountKrw,
                        "orderId", orderId, "orderName", orderName));
        return res.path("paymentKey").asText();
    }

    private JsonNode post(String path, Map<String, Object> body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", basicAuth)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = MAPPER.readTree(response.body());
            if (response.statusCode() / 100 != 2) {
                // 토스 오류 응답: {code, message} — message 가 결제 원장의 fail_reason
                String message = json.path("message").asText("PG 오류 (HTTP " + response.statusCode() + ")");
                log.warn("toss {} 거절: {} {}", path, json.path("code").asText(), message);
                throw new PaymentGatewayException(message);
            }
            return json;
        } catch (PaymentGatewayException e) {
            throw e;
        } catch (Exception e) {
            log.error("toss {} 호출 실패", path, e);
            throw new PaymentGatewayException("결제 서버와 통신하지 못했습니다.");
        }
    }
}
