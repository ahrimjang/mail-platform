package io.github.ahrimjang.mail.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DLQ 봉투 해석 — 발송 잡과 팬아웃 잡이 같은 큐로 오므로 여기서 틀리면 엉뚱한 행을
 * FAILED 처리한다. RabbitMQ 가 붙이는 x-death 형태를 그대로 흉내 낸다.
 */
class DeadLetterListenerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static Message dead(String json, String typeId, String originQueue, long count) {
        MessageProperties props = new MessageProperties();
        props.setHeader("x-death", List.of(Map.of("queue", originQueue, "count", count, "reason", "rejected")));
        if (typeId != null) {
            props.setHeader("__TypeId__", typeId);
        }
        return new Message(json.getBytes(StandardCharsets.UTF_8), props);
    }

    @Test
    void sendJob_isRecognisedByTypeIdAndBody() throws Exception {
        var env = DeadLetterListener.Envelope.parse(
                dead("{\"messageId\":42}", "io.github.ahrimjang.mail.common.SendJob", "mail.send.queue", 3), mapper);

        assertThat(env.type()).isEqualTo("send");
        assertThat(env.id()).isEqualTo(42L);
        assertThat(env.reason()).isEqualTo("mail.send.queue 에서 3회 rejected");
    }

    @Test
    void fanoutJob_isRecognisedByOriginQueue_evenWithoutTypeId() throws Exception {
        var env = DeadLetterListener.Envelope.parse(
                dead("{\"campaignId\":7}", null, "mail.fanout.queue", 3), mapper);

        assertThat(env.type()).isEqualTo("fanout");
        assertThat(env.id()).isEqualTo(7L);
    }

    @Test
    void unknownBody_isFlagged_notMisrouted() throws Exception {
        var env = DeadLetterListener.Envelope.parse(
                dead("{\"something\":1}", "com.example.Other", "mail.send.queue", 1), mapper);

        assertThat(env.type()).isEqualTo("unknown");
        assertThat(env.id()).isNull();
    }

    @Test
    void missingXDeath_stillParses_withUnknownOrigin() throws Exception {
        Message m = new Message("{\"messageId\":5}".getBytes(StandardCharsets.UTF_8), new MessageProperties());

        var env = DeadLetterListener.Envelope.parse(m, mapper);

        assertThat(env.type()).isEqualTo("send");
        assertThat(env.reason()).startsWith("원본 큐 미상");
    }
}
