package dev.tracepanel.spring;

import dev.tracepanel.core.Widget;
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
import java.util.function.Supplier;

/**
 * The response as the app writes it: an HTML page is held until it is complete, so the widget can go into its head;
 * anything else (JSON, a compressed page, an async answer) goes out as it is written, its late headers set just before.
 * A page the app flushes, or one past the container's buffer, is a stream: it goes out from there as it is written,
 * with the widget put in as it passes, unless the app declared its size.
 */
final class TracedResponse extends HttpServletResponseWrapper {
    private final Runnable beforeCommit;
    private final Supplier<String> script;
    private ByteArrayOutputStream page;
    private ServletOutputStream stream;
    private PrintWriter writer;
    private boolean started;
    /** The app declared the page's size: it stays held, and goes out with the widget and its new size. */
    private boolean sized;
    /** The page goes out as it is written, through `streamed` when the widget goes into it. */
    private boolean streaming;
    private Widget.Stream streamed;
    /** The page is being taken at its end: flushing the writer then is not the app's flush. */
    private boolean ending;

    TracedResponse(HttpServletResponse response, Runnable beforeCommit, Supplier<String> script) {
        super(response);
        this.beforeCommit = beforeCommit;
        this.script = script;
    }

    /** The held page, or null when the response went out as it was written (a streamed page's end goes out now). */
    byte[] page() throws IOException {
        ending = true;
        if (writer != null) writer.flush();
        if (streaming && streamed != null) {
            super.getOutputStream().write(streamed.end());
            streamed = null;
        }
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
        return type != null && type.toLowerCase(Locale.ROOT).startsWith("text/html") && getHeader("Content-Encoding") == null;
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

    /** A held page's bytes, or a streamed one's on their way out. */
    private void write(byte[] bytes, int offset, int length) throws IOException {
        if (streaming) {
            if (streamed == null) super.getOutputStream().write(bytes, offset, length);
            else super.getOutputStream().write(streamed.feed(bytes, offset, length));
            return;
        }
        // Dropped for a redirect: what comes after goes where the container sends it.
        if (page == null) {
            super.getOutputStream().write(bytes, offset, length);
            return;
        }
        page.write(bytes, offset, length);
        // The container would have sent it by now: from here it is a stream.
        if (!sized && page.size() > getBufferSize()) stream();
    }

    /** The app's flush: a held page goes out from here on, the widget put in as it passes. */
    private void flushed() throws IOException {
        if (!streaming && (ending || sized)) return;
        if (!streaming && page != null) stream();
        super.getOutputStream().flush();
    }

    /** The held page out now, with the badge's headers (the time so far): what the app writes next follows it. */
    private void stream() throws IOException {
        streaming = true;
        beforeCommit.run();
        if (Widget.streamable(getContentType(), getHeader("Content-Encoding"), false)) streamed = new Widget.Stream(script.get());
        byte[] held = page.toByteArray();
        page = null;
        write(held, 0, held.length);
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        start();
        if (page == null && !streaming) return super.getOutputStream();
        if (stream == null) {
            stream = new ServletOutputStream() {
                @Override
                public void write(int b) throws IOException {
                    TracedResponse.this.write(new byte[] {(byte) b}, 0, 1);
                }

                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    TracedResponse.this.write(bytes, offset, length);
                }

                @Override
                public void flush() throws IOException {
                    flushed();
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
        if (page == null && !streaming) return super.getWriter();
        if (writer == null) writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), charset()));
        return writer;
    }

    @Override
    public void flushBuffer() throws IOException {
        start();
        if (page == null && !streaming) {
            super.flushBuffer();
            return;
        }
        if (writer != null) writer.flush();
        flushed();
    }

    /**
     * A redirect commits the response at once (a controller's "redirect:", Spring Security's): its headers, the
     * redirect cookie among them, go on before, with the status it goes out with.
     */
    private void redirecting(int status) {
        if (isCommitted()) return;
        // A page held so far is dropped, as the container drops its buffer for the redirect.
        page = null;
        started = true;
        super.setStatus(status);
        beforeCommit.run();
    }

    @Override
    public void sendRedirect(String location) throws IOException {
        redirecting(SC_FOUND);
        super.sendRedirect(location);
    }

    @Override
    public void sendRedirect(String location, int status) throws IOException {
        redirecting(status);
        super.sendRedirect(location, status);
    }

    @Override
    public void sendRedirect(String location, boolean clearBuffer) throws IOException {
        redirecting(SC_FOUND);
        super.sendRedirect(location, clearBuffer);
    }

    @Override
    public void sendRedirect(String location, int status, boolean clearBuffer) throws IOException {
        redirecting(status);
        super.sendRedirect(location, status, clearBuffer);
    }

    @Override
    public void setContentLength(int length) {
        // A held page's length changes with the widget: set when it goes out.
        if (html()) sized = true;
        else super.setContentLength(length);
    }

    @Override
    public void setContentLengthLong(long length) {
        if (html()) sized = true;
        else super.setContentLengthLong(length);
    }

    @Override
    public void setHeader(String name, String value) {
        if (!length(name)) super.setHeader(name, value);
    }

    @Override
    public void addHeader(String name, String value) {
        if (!length(name)) super.addHeader(name, value);
    }

    @Override
    public void setIntHeader(String name, int value) {
        if (!length(name)) super.setIntHeader(name, value);
    }

    @Override
    public void addIntHeader(String name, int value) {
        if (!length(name)) super.addIntHeader(name, value);
    }

    /** Whether a header is a page's Content-Length, which says it is sized and is set when the page goes out. */
    private boolean length(String name) {
        if (!"Content-Length".equalsIgnoreCase(name) || !html()) return false;
        sized = true;
        return true;
    }
}
