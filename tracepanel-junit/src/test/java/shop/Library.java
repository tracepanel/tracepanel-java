package shop;

import dev.tracepanel.core.Jdbc;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;

/** The examples' database: H2 in memory, its connections recorded as an app's are. */
public final class Library {
    public static final DataSource DB = Jdbc.dataSource(h2(), "default");

    static {
        try (Connection connection = DB.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS author (id INT PRIMARY KEY, name VARCHAR(50))");
            statement.execute("CREATE TABLE IF NOT EXISTS book (id INT PRIMARY KEY, title VARCHAR(50), author_id INT)");
            statement.execute("MERGE INTO author KEY (id) VALUES (1, 'Ann'), (2, 'Bob'), (3, 'Cy'), (4, 'Di')");
            statement.execute("MERGE INTO book KEY (id) VALUES (1, 'One', 1), (2, 'Two', 2), (3, 'Three', 3), (4, 'Four', 4)");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private Library() {}

    private static DataSource h2() {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:library;DB_CLOSE_DELAY=-1");
        return h2;
    }

    /** One query: the authors' names. */
    public static List<String> authors() {
        return strings("SELECT name FROM author ORDER BY id");
    }

    /** The books with their authors, an author's name a query each: an N+1. */
    public static List<String> titlesWithAuthors() {
        List<String> titles = new ArrayList<>();
        try (Connection connection = DB.getConnection()) {
            List<int[]> books = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT id, author_id FROM book ORDER BY id")) {
                while (rows.next()) books.add(new int[] {rows.getInt(1), rows.getInt(2)});
            }
            for (int[] book : books) {
                try (PreparedStatement author = connection.prepareStatement("SELECT name FROM author WHERE id = ?")) {
                    author.setInt(1, book[1]);
                    try (ResultSet rows = author.executeQuery()) {
                        rows.next();
                        titles.add(book[0] + " by " + rows.getString(1));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return titles;
    }

    private static List<String> strings(String sql) {
        List<String> found = new ArrayList<>();
        try (Connection connection = DB.getConnection(); Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) found.add(rows.getString(1));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return found;
    }
}
