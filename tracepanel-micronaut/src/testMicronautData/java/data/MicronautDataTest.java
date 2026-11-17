package data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tracepanel.testing.FakeAgent;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

// A @Transactional method's statements, through Micronaut Data's transaction-aware DataSource: one transaction holds
// them, committed.
@MicronautTest(environments = "dev", transactional = false)
@Property(name = "datasources.default.url", value = "jdbc:h2:mem:data;DB_CLOSE_DELAY=-1")
@Property(name = "datasources.default.driver-class-name", value = "org.h2.Driver")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MicronautDataTest implements TestPropertyProvider {
    @Inject @Client("/") HttpClient http;

    @Override
    public Map<String, String> getProperties() {
        try {
            FakeAgent.start();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        return Map.of();
    }

    @Test
    void aTransactionHoldsItsStatements() throws Exception {
        assertEquals("moved", http.toBlocking().retrieve("/accounts/transfer"));
        Map<String, Object> trace = FakeAgent.traceAt("/accounts/transfer");

        assertEquals("ok", trace.get("status"));
        assertEquals(List.of(
                "http GET /accounts/transfer",
                "transaction commit",
                "sql SELECT 1",
                "sql SELECT 2"), ((List<?>) trace.get("spans")).stream().map(MicronautDataTest::said).toList());
    }

    /** A span as the test reads it: its kind and what it is (a transaction's outcome, a statement). */
    private static String said(Object value) {
        Map<?, ?> span = (Map<?, ?>) value;
        Map<?, ?> attrs = (Map<?, ?>) span.get("attrs");
        return span.get("kind") + " " + switch (String.valueOf(span.get("kind"))) {
            case "transaction" -> attrs.get("db.transaction.outcome");
            case "sql" -> attrs.get("db.statement");
            default -> span.get("name");
        };
    }

    @Controller("/accounts")
    @ExecuteOn(TaskExecutors.BLOCKING)
    static class AccountsController {
        private final DataSource database;

        AccountsController(DataSource database) { this.database = database; }

        @Get("/transfer")
        @Transactional
        String transfer() throws SQLException {
            for (String statement : List.of("SELECT 1", "SELECT 2")) {
                try (var connection = database.getConnection(); var query = connection.prepareStatement(statement);
                        var rows = query.executeQuery()) {
                    rows.next();
                }
            }
            return "moved";
        }
    }
}
