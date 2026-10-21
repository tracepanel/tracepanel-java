package shop;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import lib.Shelf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BookController {
    private static final Logger log = LoggerFactory.getLogger(BookController.class);
    private final JdbcTemplate db;

    public BookController(JdbcTemplate db) {
        this.db = db;
    }

    /** One author query per book: an N+1. */
    @GetMapping("/shelves/{shelf}/books")
    public List<Map<String, Object>> books(@PathVariable String shelf) {
        log.info("listing shelf {}", shelf);
        List<Map<String, Object>> books = db.queryForList("SELECT id, title, author_id FROM book ORDER BY id");
        for (Map<String, Object> book : books) {
            book.put("author", db.queryForObject("SELECT name FROM author WHERE id = ?", String.class, book.get("AUTHOR_ID")));
        }
        return books;
    }

    @GetMapping(value = "/page", produces = MediaType.TEXT_HTML_VALUE)
    public String page() {
        return "<!doctype html><html><head><title>Books</title></head><body>"
                + db.queryForObject("SELECT COUNT(*) FROM book", Integer.class) + " books</body></html>";
    }

    /**
     * Counted by library code as the answer is written, not by the handler: on the async dispatch, where no code of the
     * app's runs (its filters only run on the request's own).
     */
    @GetMapping("/shelves/count")
    public Callable<Shelf> count() {
        return () -> new Shelf(db);
    }

    /** A form's POST that redirects to the page, as "redirect:" does (sendRedirect). */
    @PostMapping("/shelves")
    public RedirectView shelve() {
        db.queryForObject("SELECT COUNT(*) FROM book", Integer.class);
        return new RedirectView("/page");
    }

    /** A redirect the handler answers itself, without sendRedirect. */
    @PostMapping("/shelves/other")
    public ResponseEntity<Void> shelveOther() {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create("/page")).build();
    }

    @GetMapping("/missing")
    public String missing() {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such shelf");
    }

    @GetMapping("/boom")
    public String boom() {
        throw new IllegalStateException("the shelf is on fire");
    }
}
