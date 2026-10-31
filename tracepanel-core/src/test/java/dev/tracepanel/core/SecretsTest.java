package dev.tracepanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Values under a secret's name, masked before they are recorded, as the Python SDK masks them. */
class SecretsTest {
    private static final String M = Secrets.MASK;

    @Test
    void a_url_loses_its_credentials_and_its_secret_query_values() {
        List<String> urls = List.of(
                "https://user:hunter2@api.example.com/v1/charges?amount=10&api_key=sk_live_1&Token=abc",
                "http://127.0.0.1:8001/rates?from=EUR&to=USD",
                "https://api.example.com/hooks?access_token=&flag",
                "https://api.example.com/path#section");

        List<String> recorded = urls.stream().map(url -> Secrets.url(URI.create(url))).toList();

        assertEquals(List.of(
                "https://api.example.com/v1/charges?amount=10&api_key=" + M + "&Token=" + M,
                "http://127.0.0.1:8001/rates?from=EUR&to=USD",
                "https://api.example.com/hooks?access_token=" + M + "&flag",
                "https://api.example.com/path"), recorded);
    }

    @Test
    void a_parameter_compared_with_a_secret_column_is_masked() {
        String sql = "SELECT id FROM users WHERE email = ? AND \"password_hash\" = ? AND u.api_key IN (?, ?) AND age > ?";

        List<Object> recorded = Secrets.params(sql, Arrays.asList("a@b.c", "$2a$10$x", "k1", "k2", 30));

        assertEquals(Arrays.asList("a@b.c", M, M, "k2", 30), recorded);
    }

    @Test
    void an_inserted_value_is_masked_by_its_column() {
        String sql = "INSERT INTO users (email, password, created) VALUES (?, ?, now())";

        assertEquals(Arrays.asList("a@b.c", M), Secrets.params(sql, Arrays.asList("a@b.c", "hunter2")));
    }

    @Test
    void an_update_set_and_quoted_question_marks() {
        String sql = "UPDATE sessions SET note = '?', token = ? WHERE id = ?";

        assertEquals(Arrays.asList(M, 7), Secrets.params(sql, Arrays.asList("t0k", 7)));
    }

    @Test
    void nothing_to_mask_and_nulls_stay_as_they_are() {
        assertEquals(Arrays.asList(1, "x"), Secrets.params("SELECT * FROM book WHERE id = ? AND title = ?", Arrays.asList(1, "x")));
        assertEquals(Arrays.asList((Object) null), Secrets.params("SELECT 1 FROM u WHERE password = ?", Arrays.asList((Object) null)));
    }

    @Test
    void a_name_is_a_secret_s_word_by_word_as_the_python_sdk_reads_it() {
        List<String> names = List.of("password", "apiKey", "x-api-key", "Authorization", "csrftoken", "sessionId",
                "card_number", "cvc", "accessTokens", "tokens", "author", "tokenizer", "passwordLength", "hasPassword",
                "isPrivate", "token_type", "cookieName", "maxTokens", "name");

        List<String> secret = names.stream().filter(Secrets::sensitive).toList();

        assertEquals(List.of("password", "apiKey", "x-api-key", "Authorization", "csrftoken", "sessionId", "card_number",
                "cvc", "accessTokens", "tokens"), secret);
        assertEquals(true, Secrets.counted("tokens", 1024));
        assertEquals(true, Secrets.counted("tokens", List.of(12, 7)));
        assertEquals(false, Secrets.counted("tokens", List.of("abc")));
        assertEquals(false, Secrets.counted("password", 1234));
    }
}
