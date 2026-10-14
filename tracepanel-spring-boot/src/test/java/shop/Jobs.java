package shop;

import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The app's work besides requests: a task a request queues, a scheduled job, and a runner at startup. */
@Component
public class Jobs implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(Jobs.class);
    private final JdbcTemplate db;

    public Jobs(JdbcTemplate db) {
        this.db = db;
    }

    @Async
    public void sendDigest(String to) {
        log.info("digest for {}", to);
        db.queryForObject("SELECT 1", Integer.class);
    }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.HOURS)
    public void nightly() {
        db.queryForObject("SELECT 2", Integer.class);
    }

    @Override
    public void run(String... args) {
        db.queryForObject("SELECT 3", Integer.class);
    }

    @RestController
    static class Queue {
        private final Jobs jobs;

        Queue(Jobs jobs) {
            this.jobs = jobs;
        }

        @GetMapping("/queue")
        String queue() {
            jobs.sendDigest("ann@example.com");
            return "queued";
        }
    }
}
