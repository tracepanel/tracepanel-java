package shop;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Messages sent from a request, one per book (an N+1 of publishes), and the listeners that receive them. */
@RestController
public class MessagingController {
    private final KafkaTemplate<String, String> kafka;
    private final RabbitTemplate rabbit;

    public MessagingController(KafkaTemplate<String, String> kafka, RabbitTemplate rabbit) {
        this.kafka = kafka;
        this.rabbit = rabbit;
    }

    @GetMapping("/publish/kafka")
    public String kafka() {
        for (String book : List.of("One", "Two", "Three")) kafka.send("books", book);
        return "sent";
    }

    @GetMapping("/publish/rabbit")
    public String rabbit() {
        for (String book : List.of("One", "Two", "Three")) rabbit.convertAndSend("books", book);
        return "sent";
    }

    @Component
    static class Listeners {
        static final List<String> RECEIVED = new CopyOnWriteArrayList<>();

        @KafkaListener(topics = "books", groupId = "shop")
        void onKafkaBook(String book) {
            RECEIVED.add("kafka " + book);
        }

        @RabbitListener(queuesToDeclare = @Queue("books"))
        void onRabbitBook(String book) {
            RECEIVED.add("rabbit " + book);
        }
    }
}
