package shop;

import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Pages that go out in pieces: each waits after its first until the client has read it, and says if it never did. */
@RestController
public class StreamController {
    static final String PAGE = "<!doctype html><html><head><title>t</title></head><body>x</body></html>";
    /** Past the container's buffer: the first piece goes out without a flush. */
    static final String FILLER = "x".repeat(10_000);
    static volatile CountDownLatch firstRead = new CountDownLatch(1);

    private static String waited() throws IOException {
        try {
            return firstRead.await(10, TimeUnit.SECONDS) ? "" : "(the first piece never arrived)";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    @GetMapping("/streamed/flushed")
    public void flushed(HttpServletResponse response) throws IOException {
        response.setContentType("text/html;charset=UTF-8");
        PrintWriter out = response.getWriter();
        out.write("<!doctype html><html><he");
        response.flushBuffer();
        out.write(waited() + "ad><title>t</title></head>");
        out.write("<body>x</body></html>");
    }

    @GetMapping("/streamed/body")
    public ResponseEntity<StreamingResponseBody> body() {
        StreamingResponseBody body = out -> {
            out.write(("<!doctype html><html><head><title>t</title></head><body>" + FILLER).getBytes(StandardCharsets.UTF_8));
            out.write((waited() + "</body></html>").getBytes(StandardCharsets.UTF_8));
        };
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(body);
    }

    @GetMapping("/streamed/compressed")
    public void compressed(HttpServletResponse response) throws IOException {
        response.setContentType("text/html");
        response.setHeader("Content-Encoding", "gzip");
        byte[] page = gzip(PAGE);
        response.getOutputStream().write(page, 0, 10);
        response.flushBuffer();
        response.getOutputStream().write(page, 10, page.length - 10);
    }

    static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }
}
