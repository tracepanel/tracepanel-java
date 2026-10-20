package dev.tracepanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The widget put into an HTML page streamed in pieces, right after its head (or body) opens. */
class WidgetStreamTest {
    private static final String TAG = "<script></script>";

    private static List<String> through(String... pieces) {
        Widget.Stream script = new Widget.Stream(TAG);
        List<String> out = new ArrayList<>();
        for (String piece : pieces) out.add(new String(script.feed(piece.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
        out.add(new String(script.end(), StandardCharsets.UTF_8));
        return out;
    }

    @Test
    void the_script_goes_right_after_the_head_opens() {
        assertEquals(List.of("<!doctype html><html><head><script></script><title>t</title>", "</head><body>x</body>", ""),
                through("<!doctype html><html><head><title>t</title>", "</head><body>x</body>"));
    }

    @Test
    void a_tag_split_between_pieces_is_held_until_it_ends() {
        assertEquals(List.of("<html>", "<head class=\"x\"><script></script>", "<body>", ""),
                through("<html><he", "ad class=\"x\">", "<body>"));
    }

    @Test
    void without_a_head_the_script_goes_after_the_body_opens() {
        assertEquals("<html><body><script></script>hi</body>", String.join("", through("<html><body>", "hi</body>")));
    }

    @Test
    void a_header_element_is_not_the_head() {
        assertEquals("<header>a</header><head><script></script>", String.join("", through("<header>a</header><head>")));
    }

    @Test
    void a_page_without_either_far_enough_in_passes_as_it_came() {
        String[] pieces = {"<p>" + "x".repeat(40_000), "<p>" + "y".repeat(40_000), "<head>"};

        assertEquals(String.join("", pieces), String.join("", through(pieces)));
    }

    @Test
    void what_is_held_at_the_end_goes_out_then() {
        assertEquals(List.of("text ", "<unfinished"), through("text <unfinished"));
    }

    @Test
    void a_character_cut_between_pieces_comes_out_whole() {
        byte[] page = "<head><title>café</title>".getBytes(StandardCharsets.UTF_8);
        int cut = "<head><title>caf".length() + 1;
        Widget.Stream script = new Widget.Stream(TAG);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        out.writeBytes(script.feed(page, 0, cut));
        out.writeBytes(script.feed(page, cut, page.length - cut));
        out.writeBytes(script.end());

        assertEquals("<head><script></script><title>café</title>", out.toString(StandardCharsets.UTF_8));
    }
}
