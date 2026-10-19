package dev.tracepanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentDownloadTest {
    @Test
    void the_tarball_is_on_npm_unless_a_mirror_is_named() {
        assertEquals(
                URI.create("https://registry.npmjs.org/@tracepanel/agent-linux-x64-gnu/-/agent-linux-x64-gnu-0.3.7.tgz"),
                AgentDownload.tarball(Map.of(), "linux-x64-gnu", "0.3.7"));

        assertEquals(
                URI.create("https://artifacts.corp.example/api/npm/npm/@tracepanel/agent-darwin-arm64/-/agent-darwin-arm64-0.3.7.tgz"),
                AgentDownload.tarball(
                        Map.of("TRACEPANEL_NPM_REGISTRY", "https://artifacts.corp.example/api/npm/npm"), "darwin-arm64", "0.3.7"));

        assertEquals(
                URI.create("https://npm.corp.example/@tracepanel/agent-win32-x64/-/agent-win32-x64-0.3.7.tgz"),
                AgentDownload.tarball(Map.of("npm_config_registry", "https://npm.corp.example/"), "win32-x64", "0.3.7"));

        assertEquals(
                URI.create("https://ours.example/@tracepanel/agent-win32-x64/-/agent-win32-x64-0.3.7.tgz"),
                AgentDownload.tarball(
                        Map.of("TRACEPANEL_NPM_REGISTRY", "https://ours.example/", "npm_config_registry", "https://npm.example/"),
                        "win32-x64",
                        "0.3.7"));
    }

    @Test
    void the_proxy_is_read_as_curl_and_npm_read_it() {
        URI npm = URI.create("https://registry.npmjs.org/x.tgz");

        assertEquals(Optional.empty(), AgentDownload.proxy(Map.of(), npm));
        assertEquals(
                Optional.of(URI.create("http://proxy.corp:3128")),
                AgentDownload.proxy(Map.of("HTTPS_PROXY", "http://proxy.corp:3128"), npm));
        assertEquals(
                Optional.of(URI.create("http://proxy.corp:3128")),
                AgentDownload.proxy(Map.of("https_proxy", "proxy.corp:3128"), npm));
        assertEquals(Optional.empty(), AgentDownload.proxy(Map.of("HTTP_PROXY", "http://proxy.corp:3128"), npm));
        assertEquals(
                Optional.of(URI.create("http://proxy.corp:3128")),
                AgentDownload.proxy(Map.of("HTTP_PROXY", "http://proxy.corp:3128"), URI.create("http://mirror.corp/x.tgz")));
        assertEquals(
                Optional.empty(),
                AgentDownload.proxy(Map.of("HTTPS_PROXY", "http://proxy.corp:3128", "NO_PROXY", "npmjs.org"), npm));
    }

    @Test
    void no_proxy_lists_hosts_and_the_domains_above_them() {
        assertTrue(AgentDownload.bypassed("localhost,.corp.example", "mirror.corp.example"));
        assertTrue(AgentDownload.bypassed("corp.example", "corp.example"));
        assertTrue(AgentDownload.bypassed("*.corp.example", "a.b.corp.example"));
        assertTrue(AgentDownload.bypassed(" registry.npmjs.org:443 ", "registry.npmjs.org"));
        assertTrue(AgentDownload.bypassed("*", "registry.npmjs.org"));

        assertFalse(AgentDownload.bypassed("corp.example", "notcorp.example"));
        assertFalse(AgentDownload.bypassed("", "registry.npmjs.org"));
        assertFalse(AgentDownload.bypassed(null, "registry.npmjs.org"));
    }

    @Test
    void the_agent_comes_through_the_proxy_the_environment_names(@TempDir Path cache) throws IOException {
        String platform = AgentDownload.platform();
        String binary = platform.startsWith("win32-") ? "tracepanel-agent.exe" : "tracepanel-agent";
        byte[] tarball = tgz("package/bin/" + binary, "#!/bin/sh\n".getBytes(StandardCharsets.US_ASCII));
        Properties known = new Properties();
        known.setProperty("version", "0.0.1");
        known.setProperty(platform, "sha512-" + Base64.getEncoder().encodeToString(sha512(tarball)));
        List<String> asked = new ArrayList<>();
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> {
            asked.add(exchange.getRequestURI() + " " + exchange.getRequestHeaders().getFirst("Proxy-Authorization"));
            if (exchange.getRequestHeaders().getFirst("Proxy-Authorization") == null) {
                exchange.getResponseHeaders().add("Proxy-Authenticate", "Basic realm=\"corp\"");
                exchange.sendResponseHeaders(407, -1);
            } else {
                exchange.sendResponseHeaders(200, tarball.length);
                exchange.getResponseBody().write(tarball);
            }
            exchange.close();
        });
        proxy.start();
        try {
            Path downloaded = new AgentDownload(Map.of(
                            "TRACEPANEL_CACHE_DIR", cache.toString(),
                            "TRACEPANEL_NPM_REGISTRY", "http://mirror.corp.example/npm/",
                            "HTTP_PROXY", "http://dev%40corp:s3+cret@127.0.0.1:" + proxy.getAddress().getPort()),
                    known)
                    .binary();

            assertEquals(cache.resolve(Path.of("agent", "0.0.1", platform, binary)), downloaded);
            assertEquals("#!/bin/sh\n", Files.readString(downloaded));

            String url = "http://mirror.corp.example/npm/@tracepanel/agent-" + platform + "/-/agent-" + platform + "-0.0.1.tgz";
            String login = "Basic " + Base64.getEncoder().encodeToString("dev@corp:s3+cret".getBytes(StandardCharsets.UTF_8));
            assertEquals(List.of(url + " null", url + " " + login), asked);
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void an_https_download_goes_through_the_proxy_and_says_how_to_log_in_on_its_tunnel(@TempDir Path cache)
            throws Exception {
        Properties known = new Properties();
        known.setProperty("version", "0.0.1");
        known.setProperty(AgentDownload.platform(), "sha512-none");
        List<String> asked = new ArrayList<>();
        PrintStream stderr = System.err;
        ByteArrayOutputStream warnings = new ByteArrayOutputStream();
        try (ServerSocket proxy = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread server = new Thread(() -> {
                try (Socket client = proxy.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                    asked.add(in.readLine());
                    client.getOutputStream().write(("HTTP/1.1 407 Proxy Authentication Required\r\n"
                                    + "Proxy-Authenticate: Basic realm=\"corp\"\r\nContent-Length: 0\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                } catch (IOException ignored) {
                    // The test reads what arrived.
                }
            });
            server.start();
            System.setErr(new PrintStream(warnings, true, StandardCharsets.UTF_8));
            Path downloaded;
            try {
                downloaded = new AgentDownload(Map.of(
                                "TRACEPANEL_CACHE_DIR", cache.toString(),
                                "HTTPS_PROXY", "http://dev:s3cret@127.0.0.1:" + proxy.getLocalPort()),
                        known)
                        .binary();
            } finally {
                System.setErr(stderr);
            }
            server.join(5000);

            assertEquals(null, downloaded);
            assertEquals(List.of("CONNECT registry.npmjs.org:443 HTTP/1.1"), asked);
            assertTrue(warnings.toString(StandardCharsets.UTF_8).contains("-Djdk.http.auth.tunneling.disabledSchemes="), warnings.toString(StandardCharsets.UTF_8));
        }
    }

    private static byte[] tgz(String name, byte[] data) throws IOException {
        byte[] header = new byte[512];
        byte[] path = name.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(path, 0, header, 0, path.length);
        byte[] size = String.format("%011o", data.length).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(size, 0, header, 124, size.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(header);
            gzip.write(data);
            gzip.write(new byte[(512 - data.length % 512) % 512 + 1024]);
        }
        return out.toByteArray();
    }

    private static byte[] sha512(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-512").digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
