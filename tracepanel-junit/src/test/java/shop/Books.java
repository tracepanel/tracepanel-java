package shop;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class Books {
    private final JdbcTemplate db;

    Books(JdbcTemplate db) {
        this.db = db;
    }

    /** The books, each author's name a query of its own. */
    @GetMapping("/books")
    List<String> books() {
        return db.queryForList("SELECT author_id FROM book ORDER BY id", Integer.class).stream()
                .map(author -> db.queryForObject("SELECT name FROM author WHERE id = ?", String.class, author))
                .toList();
    }
}
