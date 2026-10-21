package lib;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Library code outside the app's packages that queries when Jackson writes it, after the handler returned: as a lazy
 * relation does. No code of the app's is on the stack then.
 */
public class Shelf {
    private final JdbcTemplate db;

    public Shelf(JdbcTemplate db) {
        this.db = db;
    }

    public int getCount() {
        return db.queryForObject("SELECT COUNT(*) FROM book", Integer.class);
    }
}
