package dev.tracepanel.integrations;

import dev.tracepanel.core.Messages;
import dev.tracepanel.core.W3c;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

/**
 * A Kafka producer interceptor (interceptor.classes): on the sending thread, the record of the publish under way
 * (Messages.publish) gets its traceparent, the trace that sent it, and its task's id in the console's Live; and the
 * profile its trace's request asked for, if it asked (Messages.PROFILE).
 */
public final class KafkaProducerHeaders implements ProducerInterceptor<Object, Object> {
    @Override
    public ProducerRecord<Object, Object> onSend(ProducerRecord<Object, Object> record) {
        Messages.Publish publish = Messages.publishing();
        if (publish != null && record.headers().lastHeader(W3c.HEADER) == null) {
            publish.destination(record.topic());
            record.headers().add(W3c.HEADER, publish.traceparent().getBytes(StandardCharsets.UTF_8));
            record.headers().add(Messages.SENT_BY, publish.sentBy().getBytes(StandardCharsets.UTF_8));
            record.headers().add(Messages.TASK_ID, publish.taskId().getBytes(StandardCharsets.UTF_8));
        }
        // A profile its trace's request asked for: the app that receives it profiles its trace too. The app's own goes.
        if (publish != null && publish.profile() != null && record.headers().lastHeader(Messages.PROFILE) == null) {
            record.headers().add(Messages.PROFILE, publish.profile().getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {}

    @Override
    public void close() {}

    @Override
    public void configure(Map<String, ?> configs) {}
}
