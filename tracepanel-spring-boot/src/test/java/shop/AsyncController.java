package shop;

import java.util.concurrent.Callable;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Answers later, on another thread: the request's trace ends with the answer and holds what the other thread did. */
@RestController
public class AsyncController {
    private final JdbcTemplate db;

    public AsyncController(JdbcTemplate db) {
        this.db = db;
    }

    @GetMapping(value = "/later/page", produces = MediaType.TEXT_HTML_VALUE)
    public Callable<String> laterPage() {
        return () -> "<!doctype html><html><head><title>Later</title></head><body>later</body></html>";
    }

    @GetMapping("/later")
    public Callable<String> later() {
        return () -> {
            Thread.sleep(50);
            return "later " + db.queryForObject("SELECT 41 + 1", Integer.class);
        };
    }
}
