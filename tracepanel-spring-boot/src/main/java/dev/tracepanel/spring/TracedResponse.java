package dev.tracepanel.spring;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The response as the app writes it: an HTML page is held until it is complete, so the widget can go into its head;
 * anything else (JSON, a stream, an async answer) goes out as it is written, its late headers set just before.
 */
final class TracedResponse extends HttpServletResponseWrapper {
    private final Runnable beforeCommit;
    private ByteArrayOutputStream page;
    private ServletOutputStream stream;
    private PrintWriter writer;
    private boolean started;

    TracedResponse(HttpServletResponse response, Runnable beforeCommit) {
        super(response);
        this.beforeCommit = beforeCommit;
    }

    /** The held page, or null when the response went out as it was written. */
    byte[] page() {
        if (writer != null) writer.flush();
        return page == null ? null : page.toByteArray();
    }

    Charset charset() {
        String name = getCharacterEncoding();
        try {
            return name == null ? StandardCharsets.UTF_8 : Charset.forName(name);
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    private boolean html() {
        String type = getContentType();
        return type != null && type.toLowerCase(Locale.ROOT).startsWith("text/html");
    }

    /** The first write decides: an HTML page is held, anything else gets its headers and goes through. */
    private void start() {
        if (started) return;
        started = true;
        if (html()) {
            page = new ByteArrayOutputStream();
        } else {
            beforeCommit.run();
        }
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        start();
        if (page == null) return super.getOutputStream();
        if (stream == null) {
            stream = new ServletOutputStream() {
                @Override
                public void write(int b) {
                    page.write(b);
                }

                @Override
                public void write(byte[] bytes, int offset, int length) {
                    page.write(bytes, offset, length);
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {
                    throw new UnsupportedOperationException("a held page is written at once");
                }
            };
        }
        return stream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        start();
        if (page == null) return super.getWriter();
        if (writer == null) writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), charset()));
        return writer;
    }

    @Override
    public void flushBuffer() throws IOException {
        start();
        if (page == null) super.flushBuffer();
    }

    @Override
    public void setContentLength(int length) {
        // A held page's length changes with the widget: set when it goes out.
        if (!html()) super.setContentLength(length);
    }

    @Override
    public void setContentLengthLong(long length) {
        if (!html()) super.setContentLengthLong(length);
    }
}
