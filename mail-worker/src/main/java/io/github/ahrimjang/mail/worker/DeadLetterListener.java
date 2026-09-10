package io.github.ahrimjang.mail.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ahrimjang.mail.core.service.DeadLetterService;
import io.github.ahrimjang.mail.infra.messaging.RabbitMailConfig;
import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * {@code mail.send.dlq} 소비자. 발송 잡과 팬아웃 잡이 같은 DLQ 로 오므로 원본 타입은
 * 헤더({@code __TypeId__})·원본 큐({@code x-death.queue})·본문 필드로 가려낸다.
 *
 * <p>원시 {@link Message} 로 받는다 — 타입별 변환에 실패하는 것 자체가 DLQ 행 사유일 수
 * 있어서 컨버터에 맡기지 않는다. 그리고 <b>여기서는 절대 예외를 밖으로 내지 않는다</b>:
 * DLQ 에는 데드레터 목적지가 없어 여기서 거부되면 메시지가 그냥 사라진다. 처리에 실패해도
 * 로그로 남기고 소비(ack)한다.
 */
@Component
public class DeadLetterListener {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterListener.class);

    private final DeadLetterService deadLetters;
    private final ObjectMapper mapper = new ObjectMapper();

    public DeadLetterListener(DeadLetterService deadLetters) {
        this.deadLetters = deadLetters;
    }

    @RabbitListener(queues = RabbitMailConfig.DLQ)
    public void onDeadLetter(Message message) {
        Envelope env;
        try {
            env = Envelope.parse(message, mapper);
        } catch (Exception e) {
            Metrics.counter("mail.dlq.received", "type", "unparseable").increment();
            log.error("DLQ 메시지를 해석하지 못했다 — 소비하고 넘어간다. body={}",
                    new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8), e);
            return;
        }
        // Grafana 경보용 — 이 카운터가 오르면 무언가 반복 실패 중이다(운영 매뉴얼 2절)
        Metrics.counter("mail.dlq.received", "type", env.type()).increment();
        try {
            switch (env.type()) {
                case "send" -> deadLetters.sendJobDead(env.id(), env.reason());
                case "fanout" -> deadLetters.fanoutJobDead(env.id(), env.reason());
                default -> log.error("알 수 없는 DLQ 메시지 타입 — 무시한다: {}", env);
            }
        } catch (Exception e) {
            log.error("DLQ 뒷정리 실패 — 메시지는 소비한다: {}", env, e);
        }
    }

    /**
     * DLQ 메시지에서 뽑아낸 것: 원본 잡 종류, 대상 id, 사람이 읽을 사유.
     *
     * @param type   "send" | "fanout" | "unknown"
     * @param id     messageId 또는 campaignId
     * @param reason x-death 요약 — 예: "mail.send.queue 에서 3회 rejected"
     */
    record Envelope(String type, Long id, String reason) {

        static Envelope parse(Message message, ObjectMapper mapper) throws IOException {
            MessageProperties props = message.getMessageProperties();
            String typeId = header(props, "__TypeId__");
            String originQueue = null;
            long count = 0;
            String cause = "rejected";
            List<Map<String, ?>> deaths = props.getXDeathHeader();
            if (deaths != null && !deaths.isEmpty()) {
                Map<String, ?> first = deaths.get(0);
                originQueue = first.get("queue") == null ? null : String.valueOf(first.get("queue"));
                cause = first.get("reason") == null ? cause : String.valueOf(first.get("reason"));
                count = first.get("count") instanceof Number n ? n.longValue() : 0;
            }
            String reason = (originQueue == null ? "원본 큐 미상" : originQueue)
                    + (count > 0 ? " 에서 " + count + "회 " : " 에서 ") + cause;

            JsonNode body = message.getBody() == null || message.getBody().length == 0
                    ? mapper.nullNode() : mapper.readTree(message.getBody());
            boolean fanout = (typeId != null && typeId.endsWith("FanoutJob"))
                    || RabbitMailConfig.FANOUT_QUEUE.equals(originQueue)
                    || (body.hasNonNull("campaignId") && !body.hasNonNull("messageId"));
            if (fanout && body.hasNonNull("campaignId")) {
                return new Envelope("fanout", body.get("campaignId").asLong(), reason);
            }
            if (body.hasNonNull("messageId")) {
                return new Envelope("send", body.get("messageId").asLong(), reason);
            }
            return new Envelope("unknown", null, reason + " · typeId=" + typeId);
        }

        private static String header(MessageProperties props, String name) {
            Object v = props.getHeaders().get(name);
            return v == null ? null : String.valueOf(v);
        }
    }
}
