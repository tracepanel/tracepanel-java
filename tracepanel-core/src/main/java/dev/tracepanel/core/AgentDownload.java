package dev.tracepanel.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.zip.GZIPInputStream;

/**
 * The agent for a JVM app, which has no pip or npm to install it: the SDK's own version of it, downloaded once (the
 * same binary as on PyPI and npm), checked against the checksum this SDK was built with,
 * and kept in the user's cache (~/.cache/tracepanel/agent/VERSION/PLATFORM/), shared by every project. It comes from
 * Maven Central, or the company's mirror of it, and from npm when that fails. Behind a proxy it goes through
 * HTTPS_PROXY (or the JVM's own proxy settings). The checksum is the binary's: the same wherever it comes from.
 */
public final class AgentDownload {
    private static final String NPM = "https://registry.npmjs.org/";
    private static final String CENTRAL = "https://repo1.maven.org/maven2/";
    private static final int REDIRECTS = 5;

    /** Where a file is, and the Authorization header that repository takes (null: none). */
    record Repository(URI url, String authorization) {
        Repository resolve(String path) {
            String base = url.toString();
            return new Repository(URI.create((base.endsWith("/") ? base : base + "/") + path), authorization);
        }
    }
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final Map<String, String> env;
    private final Properties known;
    private final Path home;

    AgentDownload(Map<String, String> env) {
        this(env, load(), Path.of(System.getProperty("user.home", ".")));
    }

    /** With the agent's version and checksums, and the home Maven's settings are read from, given: the tests' own. */
    AgentDownload(Map<String, String> env, Properties known, Path home) {
        this.env = env;
        this.known = known;
        this.home = home;
    }

    /**
     * The agent's binary for this platform: in the cache, else downloaded there now, from a Maven repository (Maven
     * Central, or the mirror Maven's settings name) or, failing that, from npm; null when it can't be had.
     */
    synchronized Path binary() {
        String platform = platform();
        String version = known.getProperty("version");
        String integrity = platform == null ? null : known.getProperty(platform);
        if (version == null || integrity == null) return null;
        Path binary = cache().resolve(Path.of("agent", version, platform, executable(platform)));
        if (Files.isExecutable(binary)) return binary;
        Agent.warn("downloading the agent " + version + " for " + platform + " (once, into " + binary.getParent() + ")");
        List<String> failures = new ArrayList<>();
        Repository maven = repository(env, home);
        Repository npm = new Repository(tarball(env, platform, version), null);
        for (Repository from : List.of(maven.resolve(artifact(platform, version)), npm)) {
            try {
                byte[] fetched = fetch(from);
                byte[] file = from == npm ? entry(fetched, "package/bin/" + executable(platform)) : fetched;
                if (file == null || !integrity.equals("sha512-" + Base64.getEncoder().encodeToString(sha512(file)))) {
                    failures.add(from.url() + ": not the agent this SDK was built with (its checksum differs)");
                    continue;
                }
                save(binary, file);
                return binary;
            } catch (IOException | RuntimeException e) {
                failures.add(from.url() + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        Agent.warn("the agent couldn't be downloaded. " + String.join("; ", failures) + ". Behind a proxy, set"
                + " HTTPS_PROXY; with a Maven mirror, TRACEPANEL_MAVEN_REPOSITORY (or a mirror in ~/.m2/settings.xml);"
                + " or set TRACEPANEL_AGENT_BIN to an agent's binary");
        return null;
    }

    private static void save(Path binary, byte[] file) throws IOException {
        Files.createDirectories(binary.getParent());
        Path partial = Files.createTempFile(binary.getParent(), "tracepanel-agent", ".part");
        Files.write(partial, file);
        if (!partial.toFile().setExecutable(true)) Agent.warn("couldn't make " + partial + " executable");
        // Another process may download it too: the last whole file wins, never a half-written one.
        Files.move(partial, binary, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** npm's name for this platform, as the agent's packages are named; null where there is no agent. */
    static String platform() {
        return platform(System.getProperty("os.name", ""), System.getProperty("os.arch", ""), isMusl());
    }

    static String platform(String osName, String osArch, boolean musl) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = switch (osArch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64" -> "x64";
            case "aarch64", "arm64" -> "arm64";
            case "x86", "i386", "i486", "i586", "i686" -> "ia32";
            case "ppc64le" -> "ppc64";
            case "s390x" -> "s390x";
            case "riscv64" -> "riscv64";
            case "arm" -> "arm";
            default -> null;
        };
        if (arch == null) return null;
        if (os.startsWith("mac") || os.startsWith("darwin")) {
            return arch.equals("arm64") || arch.equals("x64") ? "darwin-" + arch : null;
        }
        if (os.startsWith("windows")) {
            return arch.equals("x64") || arch.equals("ia32") || arch.equals("arm64") ? "win32-" + arch : null;
        }
        if (!os.startsWith("linux")) return null;
        // 32-bit ARM has one static (musl) build, which runs on either.
        if (arch.equals("arm")) return "linux-arm";
        if (musl) return arch.equals("x64") || arch.equals("arm64") || arch.equals("ia32") ? "linux-" + arch + "-musl" : null;
        return "linux-" + arch + "-gnu";
    }

    /** Linux on musl (Alpine and the like): its dynamic loader is ld-musl. */
    private static boolean isMusl() {
        try (var files = Files.list(Path.of("/lib"))) {
            return files.anyMatch(file -> file.getFileName().toString().startsWith("ld-musl-"));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static String executable(String platform) {
        return platform.startsWith("win32-") ? "tracepanel-agent.exe" : "tracepanel-agent";
    }

    /** TRACEPANEL_CACHE_DIR, else the user's cache: %LOCALAPPDATA% on Windows, $XDG_CACHE_HOME or ~/.cache elsewhere. */
    private Path cache() {
        String named = env.get("TRACEPANEL_CACHE_DIR");
        if (named != null && !named.isBlank()) return Path.of(named);
        String local = env.get("LOCALAPPDATA");
        if (local != null && !local.isBlank()) return Path.of(local, "tracepanel");
        String xdg = env.get("XDG_CACHE_HOME");
        if (xdg != null && !xdg.isBlank()) return Path.of(xdg, "tracepanel");
        return Path.of(System.getProperty("user.home"), ".cache", "tracepanel");
    }

    /** The platform's binary in a Maven repository: dev.tracepanel:tracepanel-agent, the platform its classifier. */
    static String artifact(String platform, String version) {
        return "dev/tracepanel/tracepanel-agent/" + version + "/tracepanel-agent-" + version + "-" + platform + ".exe";
    }

    /**
     * The Maven repository the agent comes from: TRACEPANEL_MAVEN_REPOSITORY (a login in its URL), else the mirror of
     * Maven Central that ~/.m2/settings.xml names, with its server's login, else Maven Central.
     */
    static Repository repository(Map<String, String> env, Path home) {
        String named = first(env, "TRACEPANEL_MAVEN_REPOSITORY");
        if (named != null) {
            URI url = URI.create(named);
            if (url.getRawUserInfo() == null) return new Repository(url, null);
            URI bare = URI.create(url.getScheme() + "://" + url.getRawAuthority().substring(url.getRawUserInfo().length() + 1)
                    + (url.getRawPath() == null ? "" : url.getRawPath()));
            return new Repository(bare, basic(decode(url.getRawUserInfo())));
        }
        Repository mirror = MavenSettings.mirror(home.resolve(Path.of(".m2", "settings.xml")), env);
        return mirror != null ? mirror : new Repository(URI.create(CENTRAL), null);
    }

    static String basic(String userAndPassword) {
        return "Basic " + Base64.getEncoder().encodeToString(userAndPassword.getBytes(StandardCharsets.UTF_8));
    }

    /** Where the platform's tarball is: TRACEPANEL_NPM_REGISTRY, else npm's own setting (npm_config_registry), else npm. */
    static URI tarball(Map<String, String> env, String platform, String version) {
        String registry = first(env, "TRACEPANEL_NPM_REGISTRY", "npm_config_registry", "NPM_CONFIG_REGISTRY");
        String base = registry == null ? NPM : registry.endsWith("/") ? registry : registry + "/";
        return URI.create(base + "@tracepanel/agent-" + platform + "/-/agent-" + platform + "-" + version + ".tgz");
    }

    /**
     * The proxy the environment names for `target`, as curl and npm read it: HTTPS_PROXY for https, HTTP_PROXY for
     * http (or their lower-case forms), unless NO_PROXY lists the host. Empty: none named.
     */
    static Optional<URI> proxy(Map<String, String> env, URI target) {
        if (bypassed(first(env, "NO_PROXY", "no_proxy"), target.getHost())) return Optional.empty();
        String proxy = "https".equals(target.getScheme())
                ? first(env, "HTTPS_PROXY", "https_proxy")
                : first(env, "HTTP_PROXY", "http_proxy");
        if (proxy == null) return Optional.empty();
        return Optional.of(URI.create(proxy.contains("://") ? proxy : "http://" + proxy));
    }

    /** Whether NO_PROXY ("localhost,.corp.example,10.0.0.1:8080", "*") lists `host`, or a domain above it. */
    static boolean bypassed(String noProxy, String host) {
        if (noProxy == null || host == null) return false;
        String name = host.toLowerCase(Locale.ROOT);
        for (String entry : noProxy.split(",")) {
            String listed = entry.trim().toLowerCase(Locale.ROOT).replaceFirst(":\\d+$", "");
            if (listed.equals("*")) return true;
            listed = listed.replaceFirst("^\\*?\\.", "");
            if (!listed.isEmpty() && (name.equals(listed) || name.endsWith("." + listed))) return true;
        }
        return false;
    }

    private static String first(Map<String, String> env, String... names) {
        for (String name : names) {
            String value = env.get(name);
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    /**
     * The file, through the proxy the environment names for it. Redirects are followed here, not by the client: a
     * repository's login goes to its own host only, never to the storage it redirects to.
     */
    private byte[] fetch(Repository from) throws IOException, InterruptedException {
        URI url = from.url();
        String authorization = from.authorization();
        for (int redirect = 0; redirect <= REDIRECTS; redirect++) {
            HttpResponse<byte[]> response = client(url).send(request(url, authorization), HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status == 200) return response.body();
            // The JDK sends no Basic login on an https tunnel unless the JVM is started with this flag.
            if (status == 407) {
                throw new IOException("HTTP 407 from the proxy; if it takes a Basic login, start the JVM with"
                        + " -Djdk.http.auth.tunneling.disabledSchemes=");
            }
            Optional<String> location = response.headers().firstValue("Location");
            if (status / 100 != 3 || location.isEmpty()) throw new IOException("HTTP " + status);
            URI next = url.resolve(location.get());
            if (!next.getScheme().equals(url.getScheme()) && !"https".equals(next.getScheme())) {
                throw new IOException("a redirect from https to " + next.getScheme());
            }
            if (!sameOrigin(url, next)) authorization = null;
            url = next;
        }
        throw new IOException("more than " + REDIRECTS + " redirects");
    }

    private static HttpRequest request(URI url, String authorization) {
        HttpRequest.Builder request = HttpRequest.newBuilder(url).timeout(TIMEOUT);
        if (authorization != null) request.header("Authorization", authorization);
        return request.build();
    }

    private HttpClient client(URI url) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(TIMEOUT);
        Optional<URI> named = proxy(env, url);
        if (named.isPresent()) {
            URI proxy = named.get();
            int port = proxy.getPort() != -1 ? proxy.getPort() : "https".equals(proxy.getScheme()) ? 443 : 80;
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(), port)));
            if (proxy.getRawUserInfo() != null) builder.authenticator(login(proxy.getRawUserInfo()));
        } else if (ProxySelector.getDefault() != null) {
            // The JVM's own: -Dhttps.proxyHost, or the system's with -Djava.net.useSystemProxies=true.
            builder.proxy(ProxySelector.getDefault());
        }
        return builder.build();
    }

    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost().equalsIgnoreCase(b.getHost())
                && port(a) == port(b);
    }

    private static int port(URI url) {
        return url.getPort() != -1 ? url.getPort() : "https".equalsIgnoreCase(url.getScheme()) ? 443 : 80;
    }

    /** The proxy's user and password from its URL (http://user:password@proxy:3128), for the proxy only. */
    private static Authenticator login(String userInfo) {
        int colon = userInfo.indexOf(':');
        String user = decode(colon < 0 ? userInfo : userInfo.substring(0, colon));
        char[] password = decode(colon < 0 ? "" : userInfo.substring(colon + 1)).toCharArray();
        return new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return getRequestorType() == RequestorType.PROXY ? new PasswordAuthentication(user, password) : null;
            }
        };
    }

    /** Percent-decoding alone: a URL's user part keeps its plus signs. */
    static String decode(String part) {
        return URLDecoder.decode(part.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static byte[] sha512(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-512").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One file of a .tgz, or null: a tar is 512-byte headers (name, octal size), each followed by its data. */
    static byte[] entry(byte[] tgz, String name) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(tgz))) {
            byte[] header = new byte[512];
            while (in.readNBytes(header, 0, 512) == 512 && header[0] != 0) {
                String path = text(header, 0, 100);
                String prefix = text(header, 345, 155);
                if (!prefix.isEmpty()) path = prefix + "/" + path;
                long size = Long.parseLong(text(header, 124, 12).trim(), 8);
                byte[] data = in.readNBytes((int) size);
                in.skipNBytes((512 - size % 512) % 512);
                if (path.equals(name)) return data;
            }
        }
        return null;
    }

    private static String text(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) end++;
        return new String(header, offset, end - offset, StandardCharsets.US_ASCII);
    }

    private static Properties load() {
        Properties properties = new Properties();
        try (InputStream in = AgentDownload.class.getResourceAsStream("agent.properties")) {
            if (in != null) properties.load(in);
        } catch (IOException ignored) {
            // No known agent: nothing is downloaded.
        }
        return properties;
    }
}
