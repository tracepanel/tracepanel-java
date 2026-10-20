package dev.tracepanel.spring;

import dev.tracepanel.core.RequestDetails;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.Part;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A request with a body, as the app reads it: the start of what it reads from the stream is kept (never more, never
 * the rest of an upload), and a form the container parses for it is noted, to be read back once the app is done.
 * Tracepanel reads nothing the app didn't. With it, what any request carried: its headers, query, cookies, session
 * and its answer's headers, under the Python SDK's names (RequestDetails).
 */
final class CapturedRequest extends HttpServletRequestWrapper {
    private static final String KEY = CapturedRequest.class.getName();

    private final RequestDetails.Kept kept;
    private ServletInputStream stream;
    private BufferedReader reader;
    // The app asked for the form's fields, or its parts: the container parsed the body for it.
    private boolean parameters;
    // Its files as the app got them: a framework deletes them once the request is done (Spring's multipart cleanup).
    private List<RequestDetails.Upload> files;

    private CapturedRequest(HttpServletRequest request) {
        super(request);
        this.kept = new RequestDetails.Kept(request.getContentLengthLong());
        request.setAttribute(KEY, this);
    }

    /**
     * The request to hand the app: wrapped when it has a body (a GET without one stays as it is: nothing to read);
     * its headers, query and cookies go into `attrs`.
     */
    static HttpServletRequest begin(HttpServletRequest request, Map<String, Object> attrs) {
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            for (String value : Collections.list(request.getHeaders(name))) headers.add(new AbstractMap.SimpleEntry<>(name, value));
        }
        attrs.putAll(RequestDetails.requestHeaders(headers));
        attrs.putAll(RequestDetails.query(request.getQueryString()));
        List<String> cookies = Collections.list(request.getHeaders("Cookie"));
        if (!cookies.isEmpty()) attrs.putAll(RequestDetails.cookies(String.join("; ", cookies)));
        String chunked = request.getHeader("Transfer-Encoding");
        boolean body = request.getContentLengthLong() > 0
                || (chunked != null && chunked.toLowerCase(Locale.ROOT).contains("chunked"));
        return body ? new CapturedRequest(request) : request;
    }

    /** What the request carried that is known at its end: the body as far as the app read it, the session, the answer's headers. */
    static Map<String, Object> end(HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        for (String name : response.getHeaderNames()) {
            for (String value : response.getHeaders(name)) headers.add(new AbstractMap.SimpleEntry<>(name, value));
        }
        attrs.putAll(RequestDetails.responseHeaders(headers));
        if (request.getAttribute(KEY) instanceof CapturedRequest captured) attrs.putAll(captured.body());
        attrs.putAll(session(request));
        return attrs;
    }

    /** The session as the app left it, if it has one: none is made here. */
    private static Map<String, Object> session(HttpServletRequest request) {
        try {
            HttpSession session = request.getSession(false);
            if (session == null) return Map.of();
            Map<String, Object> items = new LinkedHashMap<>();
            for (String name : Collections.list(session.getAttributeNames())) items.put(name, session.getAttribute(name));
            return RequestDetails.session(items);
        } catch (IllegalStateException e) {
            // Invalidated (a logout): nothing in it.
            return Map.of();
        }
    }

    /** The body: what the app read from the stream, else the form the container parsed for it. */
    private Map<String, Object> body() {
        Map<String, Object> read = kept.attrs(getContentType());
        if (!read.isEmpty() || stream != null) return read;
        String type = String.valueOf(getContentType()).toLowerCase(Locale.ROOT);
        boolean multipart = type.startsWith("multipart/form-data");
        if (!parameters && !(multipart && files != null)) return Map.of();
        if (!multipart && !type.startsWith("application/x-www-form-urlencoded")) return Map.of();
        try {
            return RequestDetails.form(fields(), multipart && files != null ? files : List.of());
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /** The parts' files, by name and size, as the container parsed them for the app. */
    private void parsed(Collection<Part> parts) {
        if (files != null) return;
        List<RequestDetails.Upload> found = new ArrayList<>();
        for (Part part : parts) {
            if (part.getSubmittedFileName() != null) {
                found.add(new RequestDetails.Upload(part.getName(), part.getSubmittedFileName(), part.getSize()));
            }
        }
        files = found;
    }

    /** The form's fields: the container's parameters without the query's, which come first. */
    private List<Map.Entry<String, String>> fields() {
        Map<String, Integer> inQuery = new HashMap<>();
        String query = getQueryString();
        if (query != null) {
            for (String part : query.split("&")) {
                if (part.isEmpty()) continue;
                int equals = part.indexOf('=');
                String name = URLDecoder.decode(equals < 0 ? part : part.substring(0, equals), StandardCharsets.UTF_8);
                inQuery.merge(name, 1, Integer::sum);
            }
        }
        List<Map.Entry<String, String>> fields = new ArrayList<>();
        super.getParameterMap().forEach((name, values) -> {
            for (int i = inQuery.getOrDefault(name, 0); i < values.length; i++) fields.add(new AbstractMap.SimpleEntry<>(name, values[i]));
        });
        return fields;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (stream == null) stream = new Tee(super.getInputStream(), kept);
        return stream;
    }

    @Override
    public BufferedReader getReader() throws IOException {
        if (reader == null) {
            String encoding = getCharacterEncoding();
            Charset charset;
            try {
                charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            } catch (RuntimeException e) {
                charset = StandardCharsets.UTF_8;
            }
            reader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
        return reader;
    }

    @Override
    public String getParameter(String name) {
        parameters = true;
        return super.getParameter(name);
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        parameters = true;
        return super.getParameterMap();
    }

    @Override
    public Enumeration<String> getParameterNames() {
        parameters = true;
        return super.getParameterNames();
    }

    @Override
    public String[] getParameterValues(String name) {
        parameters = true;
        return super.getParameterValues(name);
    }

    @Override
    public Collection<Part> getParts() throws IOException, ServletException {
        Collection<Part> found = super.getParts();
        parsed(found);
        return found;
    }

    @Override
    public Part getPart(String name) throws IOException, ServletException {
        Part found = super.getPart(name);
        parsed(super.getParts());
        return found;
    }

    /** The request's stream, its bytes kept as they pass. */
    private static final class Tee extends ServletInputStream {
        private final ServletInputStream in;
        private final RequestDetails.Kept kept;

        Tee(ServletInputStream in, RequestDetails.Kept kept) {
            this.in = in;
            this.kept = kept;
        }

        @Override
        public int read() throws IOException {
            int value = in.read();
            kept.add(value);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = in.read(buffer, offset, length);
            kept.add(buffer, offset, count);
            return count;
        }

        @Override
        public boolean isFinished() {
            return in.isFinished();
        }

        @Override
        public boolean isReady() {
            return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            in.setReadListener(listener);
        }

        @Override
        public int available() throws IOException {
            return in.available();
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
