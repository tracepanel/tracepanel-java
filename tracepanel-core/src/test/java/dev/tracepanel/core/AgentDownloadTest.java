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
    void the_maven_repository_is_named_or_the_mirror_mavens_settings_name_or_central(@TempDir Path home) throws IOException {
        assertEquals(
                new AgentDownload.Repository(URI.create("https://repo1.maven.org/maven2/"), null),
                AgentDownload.repository(Map.of(), home));
        assertEquals(
                new AgentDownload.Repository(
                        URI.create("https://artifacts.corp.example/maven2/"), AgentDownload.basic("dev@corp:s3+cret")),
                AgentDownload.repository(
                        Map.of("TRACEPANEL_MAVEN_REPOSITORY", "https://dev%40corp:s3+cret@artifacts.corp.example/maven2/"),
                        home));

        Files.createDirectories(home.resolve(".m2"));
        Files.writeString(home.resolve(".m2/settings.xml"), """
                <settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
                  <servers>
                    <server><id>other</id><username>no</username><password>no</password></server>
                    <server><id>corp</id><username>dev</username><password>${env.ARTIFACTORY_TOKEN}</password></server>
                  </servers>
                  <mirrors>
                    <mirror><id>snapshots</id><mirrorOf>snapshots</mirrorOf><url>https://no.corp.example/</url></mirror>
                    <mirror><id>corp</id><mirrorOf>*</mirrorOf><url>https://artifacts.corp.example/maven2</url></mirror>
                  </mirrors>
                </settings>
                """);
        assertEquals(
                new AgentDownload.Repository(URI.create("https://artifacts.corp.example/maven2"), AgentDownload.basic("dev:t0ken")),
                AgentDownload.repository(Map.of("ARTIFACTORY_TOKEN", "t0ken"), home));
        assertEquals(
                new AgentDownload.Repository(URI.create("https://ours.example/"), null),
                AgentDownload.repository(Map.of("TRACEPANEL_MAVEN_REPOSITORY", "https://ours.example/"), home));

        Files.writeString(home.resolve(".m2/settings.xml"), """
                <settings>
                  <servers><server><id>corp</id><username>dev</username><password>{COQLCE6DU6GtcS5P=}</password></server></servers>
                  <mirrors><mirror><id>corp</id><mirrorOf>central</mirrorOf><url>https://nexus.corp.example/</url></mirror></mirrors>
                </settings>
                """);
        assertEquals(
                new AgentDownload.Repository(URI.create("https://nexus.corp.example/"), null),
                AgentDownload.repository(Map.of(), home));
    }

    @Test
    void a_mirror_takes_in_central_by_its_mirror_of() {
        assertTrue(MavenSettings.mirrorsCentral("*"));
        assertTrue(MavenSettings.mirrorsCentral("central"));
        assertTrue(MavenSettings.mirrorsCentral("external:*"));
        assertTrue(MavenSettings.mirrorsCentral("repo1, central"));

        assertFalse(MavenSettings.mirrorsCentral("*,!central"));
        assertFalse(MavenSettings.mirrorsCentral("snapshots"));
        assertFalse(MavenSettings.mirrorsCentral(null));
    }

    @Test
    void the_agent_comes_from_the_mirror_whose_login_stays_off_its_storage(@TempDir Path cache, @TempDir Path home)
            throws IOException {
        byte[] agent = "#!/bin/sh\n".getBytes(StandardCharsets.US_ASCII);
        List<String> asked = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/maven2/", exchange -> {
            asked.add(exchange.getRequestURI() + " " + exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().add("Location", "http://localhost:" + port + "/storage/signed?x=1");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/storage/", exchange -> {
            asked.add(exchange.getRequestURI() + " " + exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, agent.length);
            exchange.getResponseBody().write(agent);
            exchange.close();
        });
        server.start();
        try {
            Path downloaded = new AgentDownload(Map.of(
                            "TRACEPANEL_CACHE_DIR", cache.toString(),
                            "TRACEPANEL_MAVEN_REPOSITORY", "http://dev:s3cret@127.0.0.1:" + port + "/maven2/"),
                    known(agent),
                    home)
                    .binary();

            assertEquals(cache.resolve(Path.of("agent", "0.0.1", PLATFORM, BINARY)), downloaded);
            assertEquals("#!/bin/sh\n", Files.readString(downloaded));
            assertEquals(
                    List.of(
                            "/maven2/" + AgentDownload.artifact(PLATFORM, "0.0.1") + " " + AgentDownload.basic("dev:s3cret"),
                            "/storage/signed?x=1 null"),
                    asked);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void without_the_agent_on_maven_it_comes_from_npm(@TempDir Path cache, @TempDir Path home) throws IOException {
        byte[] agent = "#!/bin/sh\n".getBytes(StandardCharsets.US_ASCII);
        byte[] tarball = tgz("package/bin/" + BINARY, agent);
        List<String> asked = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            asked.add(exchange.getRequestURI().toString());
            boolean npm = exchange.getRequestURI().getPath().startsWith("/npm/");
            exchange.sendResponseHeaders(npm ? 200 : 404, npm ? tarball.length : -1);
            if (npm) exchange.getResponseBody().write(tarball);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            Path downloaded = new AgentDownload(Map.of(
                            "TRACEPANEL_CACHE_DIR", cache.toString(),
                            "TRACEPANEL_MAVEN_REPOSITORY", base + "/maven2/",
                            "TRACEPANEL_NPM_REGISTRY", base + "/npm/"),
                    known(agent),
                    home)
                    .binary();

            assertEquals("#!/bin/sh\n", Files.readString(downloaded));
            assertEquals(
                    List.of(
                            "/maven2/" + AgentDownload.artifact(PLATFORM, "0.0.1"),
                            "/npm/@tracepanel/agent-" + PLATFORM + "/-/agent-" + PLATFORM + "-0.0.1.tgz"),
                    asked);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void a_binary_that_isnt_the_one_built_with_is_never_kept(@TempDir Path cache, @TempDir Path home) throws IOException {
        byte[] other = "#!/bin/sh\nrm -rf ~\n".getBytes(StandardCharsets.US_ASCII);
        byte[] tarball = tgz("package/bin/" + BINARY, other);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestURI().getPath().startsWith("/npm/") ? tarball : other;
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        PrintStream stderr = System.err;
        ByteArrayOutputStream warnings = new ByteArrayOutputStream();
        System.setErr(new PrintStream(warnings, true, StandardCharsets.UTF_8));
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            Path downloaded = new AgentDownload(Map.of(
                            "TRACEPANEL_CACHE_DIR", cache.toString(),
                            "TRACEPANEL_MAVEN_REPOSITORY", base + "/maven2/",
                            "TRACEPANEL_NPM_REGISTRY", base + "/npm/"),
                    known("#!/bin/sh\n".getBytes(StandardCharsets.US_ASCII)),
                    home)
                    .binary();

            assertEquals(null, downloaded);
            assertFalse(Files.exists(cache.resolve("agent")));
            assertTrue(warnings.toString(StandardCharsets.UTF_8).contains("its checksum differs"), warnings.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(stderr);
            server.stop(0);
        }
    }

    @Test
    void the_agent_comes_through_the_proxy_the_environment_names(@TempDir Path cache, @TempDir Path home)
            throws IOException {
        byte[] agent = "#!/bin/sh\n".getBytes(StandardCharsets.US_ASCII);
        List<String> asked = new ArrayList<>();
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> {
            asked.add(exchange.getRequestURI() + " " + exchange.getRequestHeaders().getFirst("Proxy-Authorization"));
            if (exchange.getRequestHeaders().getFirst("Proxy-Authorization") == null) {
                exchange.getResponseHeaders().add("Proxy-Authenticate", "Basic realm=\"corp\"");
                exchange.sendResponseHeaders(407, -1);
            } else {
                exchange.sendResponseHeaders(200, agent.length);
                exchange.getResponseBody().write(agent);
            }
            exchange.close();
        });
        proxy.start();
        try {
            Path downloaded = new AgentDownload(Map.of(
                            "TRACEPANEL_CACHE_DIR", cache.toString(),
                            "TRACEPANEL_MAVEN_REPOSITORY", "http://mirror.corp.example/maven2/",
                            "HTTP_PROXY", "http://dev%40corp:s3+cret@127.0.0.1:" + proxy.getAddress().getPort()),
                    known(agent),
                    home)
                    .binary();

            assertEquals("#!/bin/sh\n", Files.readString(downloaded));
            String url = "http://mirror.corp.example/maven2/" + AgentDownload.artifact(PLATFORM, "0.0.1");
            assertEquals(List.of(url + " null", url + " " + AgentDownload.basic("dev@corp:s3+cret")), asked);
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void an_https_download_goes_through_the_proxy_and_says_how_to_log_in_on_its_tunnel(
            @TempDir Path cache, @TempDir Path home) throws Exception {
        List<String> asked = new ArrayList<>();
        PrintStream stderr = System.err;
        ByteArrayOutputStream warnings = new ByteArrayOutputStream();
        try (ServerSocket proxy = new ServerSocket(0, 2, InetAddress.getLoopbackAddress())) {
            Thread server = new Thread(() -> {
                for (int connection = 0; connection < 2; connection++) {
                    try (Socket client = proxy.accept()) {
                        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                        asked.add(in.readLine());
                        client.getOutputStream().write(("HTTP/1.1 407 Proxy Authentication Required\r\n"
                                        + "Proxy-Authenticate: Basic realm=\"corp\"\r\nContent-Length: 0\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                    } catch (IOException ignored) {
                        // The test reads what arrived.
                    }
                }
            });
            server.start();
            System.setErr(new PrintStream(warnings, true, StandardCharsets.UTF_8));
            Path downloaded;
            try {
                downloaded = new AgentDownload(Map.of(
                                "TRACEPANEL_CACHE_DIR", cache.toString(),
                                "HTTPS_PROXY", "http://dev:s3cret@127.0.0.1:" + proxy.getLocalPort()),
                        known("#!/bin/sh\n".getBytes(StandardCharsets.US_ASCII)),
                        home)
                        .binary();
            } finally {
                System.setErr(stderr);
            }
            server.join(5000);

            assertEquals(null, downloaded);
            assertEquals(List.of("CONNECT repo1.maven.org:443 HTTP/1.1", "CONNECT registry.npmjs.org:443 HTTP/1.1"), asked);
            assertTrue(warnings.toString(StandardCharsets.UTF_8).contains("-Djdk.http.auth.tunneling.disabledSchemes="), warnings.toString(StandardCharsets.UTF_8));
        }
    }

    private static final String PLATFORM = AgentDownload.platform();
    private static final String BINARY = PLATFORM.startsWith("win32-") ? "tracepanel-agent.exe" : "tracepanel-agent";

    /** Version 0.0.1 of the agent, this binary on this platform. */
    private static Properties known(byte[] binary) {
        Properties known = new Properties();
        known.setProperty("version", "0.0.1");
        known.setProperty(PLATFORM, "sha512-" + Base64.getEncoder().encodeToString(sha512(binary)));
        return known;
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
