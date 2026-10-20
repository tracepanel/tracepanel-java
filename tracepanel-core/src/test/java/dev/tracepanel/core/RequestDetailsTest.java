package dev.tracepanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What a request carried, under the Python SDK's names and with its masks: the same text from the same request. */
class RequestDetailsTest {
    private static final String M = Secrets.MASK;

    private static List<Map.Entry<String, String>> pairs(String... namesAndValues) {
        List<Map.Entry<String, String>> pairs = new ArrayList<>();
        for (int i = 0; i < namesAndValues.length; i += 2) pairs.add(new AbstractMap.SimpleEntry<>(namesAndValues[i], namesAndValues[i + 1]));
        return pairs;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void headers_are_keyed_by_name_joined_and_masked_and_the_cookie_header_is_read_as_cookies() {
        Map<String, Object> request = RequestDetails.requestHeaders(pairs(
                "Host", "shop.test", "X-Api-Key", "k1", "Accept", "a", "Accept", "b", "Cookie", "x=1",
                "Authorization", "Bearer t", "Content-Type", "application/json", "Content-Length", "12"));
        Map<String, Object> bodiless = RequestDetails.requestHeaders(pairs("Content-Type", "text/plain", "User-Agent", "curl"));
        Map<String, Object> response = RequestDetails.responseHeaders(pairs(
                "Content-Type", "text/html", "Set-Cookie", "a=1", "Set-Cookie", "b=2", "X-Total", "3"));

        assertEquals(Map.of(
                "http.request.header.host", "shop.test",
                "http.request.header.x_api_key", M,
                "http.request.header.accept", "a, b",
                "http.request.header.authorization", M,
                "http.request.header.content_type", "application/json",
                "http.request.header.content_length", "12"), request);

        assertEquals(Map.of("http.request.header.user_agent", "curl"), bodiless);

        assertEquals(Map.of(
                "http.response.header.content_type", "text/html",
                "http.response.header.set_cookie", M,
                "http.response.header.x_total", "3"), response);
    }

    @Test
    void the_query_and_the_cookies_keep_their_values_but_a_secret_s() {
        Map<String, Object> query = RequestDetails.query("q=red+shoes&token=abc&page=2&empty=&a%2Fb=c%3Dd&name=J%C3%BCrgen");
        Map<String, Object> cookies = RequestDetails.cookies("theme=dark; sessionid=abc; csrftoken=x; tracepanel_profile=1");

        assertEquals(Map.of("url.query", "q=red+shoes&token=" + M + "&page=2&empty=&a/b=c%3Dd&name=J%C3%BCrgen"), query);
        assertEquals(Map.of(), RequestDetails.query(null));

        assertEquals(Map.of(
                "http.request.cookie.theme", "dark",
                "http.request.cookie.sessionid", M,
                "http.request.cookie.csrftoken", M,
                "http.request.cookie.tracepanel_profile", "1"), cookies);
    }

    @Test
    void a_json_body_is_masked_at_any_depth_and_a_form_s_fields_and_files_are_listed() {
        Map<String, Object> json = RequestDetails.body("application/json; charset=utf-8", bytes(
                "{\"user\":\"ann\",\"password\":\"p\",\"card\":{\"token\":\"t\",\"last4\":\"4242\"},\"items\":[{\"api_key\":1}],"
                        + "\"n\":1.5,\"big\":123456789012345678901234567890}"), true);
        Map<String, Object> form = RequestDetails.body("application/x-www-form-urlencoded",
                bytes("name=Ann+Lee&password=p&note=a%26b&city=K%C3%B6ln"), true);
        byte[] upload = bytes("--XyZ\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\nMy book\r\n"
                + "--XyZ\r\nContent-Disposition: form-data; name=\"secret_code\"\r\n\r\n1234\r\n"
                + "--XyZ\r\nContent-Disposition: form-data; name=\"cover\"; filename=\"cover.png\"\r\nContent-Type: image/png\r\n\r\n"
                + "-PNG12345\r\n--XyZ--\r\n");
        Map<String, Object> multipart = RequestDetails.body("multipart/form-data; boundary=XyZ", upload, true);
        Map<String, Object> parsed = RequestDetails.form(pairs("title", "My book", "pass", "x"),
                List.of(new RequestDetails.Upload("cover", "cover.png", 9)));

        assertEquals(Map.of("http.request.body", "{\"user\":\"ann\",\"password\":\"" + M + "\",\"card\":{\"token\":\"" + M
                + "\",\"last4\":\"4242\"},\"items\":[{\"api_key\":\"" + M + "\"}],\"n\":1.5,\"big\":123456789012345678901234567890}"), json);

        assertEquals(Map.of("http.request.body", "name=Ann Lee&password=" + M + "&note=a%26b&city=K%C3%B6ln"), form);

        assertEquals(Map.of("http.request.body", "title=My book&secret_code=" + M + "&cover=<file cover.png, 9 bytes>"), multipart);
        assertEquals(Map.of("http.request.body", "title=My book&pass=" + M + "&cover=<file cover.png, 9 bytes>"), parsed);
    }

    @Test
    void text_is_kept_as_it_is_binary_not_and_a_part_of_a_json_or_a_form_not_at_all() {
        assertEquals(Map.of("http.request.body", "hi"), RequestDetails.body("text/plain", bytes("hi"), true));
        assertEquals(Map.of("http.request.body", "not json"), RequestDetails.body("application/json", bytes("not json"), true));

        assertEquals(Map.of(), RequestDetails.body("application/octet-stream", bytes("x"), true));
        assertEquals(Map.of(), RequestDetails.body("application/json", bytes("{\"a\":1"), false));
        assertEquals(Map.of(), RequestDetails.body("application/json", new byte[0], true));
    }

    @Test
    void a_body_is_kept_up_to_its_limit_as_the_app_reads_it() {
        RequestDetails.Kept whole = new RequestDetails.Kept(5);
        whole.add(bytes("hello"), 0, 5);
        RequestDetails.Kept unread = new RequestDetails.Kept(5);
        unread.add(bytes("hel"), 0, 3);
        RequestDetails.Kept chunked = new RequestDetails.Kept(-1);
        for (byte b : bytes("{\"a\":1}")) chunked.add(b);
        RequestDetails.Kept large = new RequestDetails.Kept(-1);
        byte[] text = bytes("x".repeat(RequestDetails.MAX_BODY + 100));
        large.add(text, 0, text.length);

        assertEquals(Map.of("http.request.body", "hello"), whole.attrs("text/plain"));
        // Read in part: a text's start is what it is; a JSON's start is no JSON to read.
        assertEquals(Map.of("http.request.body", "hel"), unread.attrs("text/plain"));
        assertEquals(Map.of(), unread.attrs("application/json"));
        assertEquals(Map.of("http.request.body", "{\"a\":1}"), chunked.attrs("application/json"));

        assertEquals(Map.of("http.request.body", "x".repeat(RequestDetails.MAX_BODY), "http.request.body.truncated", true),
                large.attrs("text/plain"));
    }

    @Test
    void the_session_s_values_are_short_texts_a_secret_s_masked() {
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("cart", List.of("a", "b"));
        items.put("auth_token", "x");
        items.put("count", 3);
        items.put("note", "y".repeat(200));
        items.put("SPRING_SECURITY_CONTEXT", "SecurityContextImpl [SessionId=abc]");

        assertEquals(Map.of(
                "session.cart", "[a, b]",
                "session.auth_token", M,
                "session.count", "3",
                "session.note", "'" + "y".repeat(77) + "..." + "y".repeat(78) + "'",
                "session.spring_security_context", M), RequestDetails.session(items));
    }
}
