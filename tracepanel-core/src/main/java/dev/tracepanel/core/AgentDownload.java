package dev.tracepanel.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
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
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.zip.GZIPInputStream;

/**
 * The agent for a JVM app, which has no pip or npm to install it: the SDK's own version of it, downloaded once from
 * the agent's npm platform package (the same binary as on PyPI), checked against the checksum this SDK was built with,
 * and kept in the user's cache (~/.cache/tracepanel/agent/VERSION/PLATFORM/), shared by every project.
 */
public final class AgentDownload {
    private static final String REGISTRY = "https://registry.npmjs.org/@tracepanel/agent-%1$s/-/agent-%1$s-%2$s.tgz";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final Map<String, String> env;
    private final Properties known;

    AgentDownload(Map<String, String> env) {
        this.env = env;
        this.known = load();
    }

    /** The agent's binary for this platform: in the cache, else downloaded there now; null when it can't be had. */
    synchronized Path binary() {
        String platform = platform();
        String version = known.getProperty("version");
        String integrity = platform == null ? null : known.getProperty(platform);
        if (version == null || integrity == null) return null;
        Path binary = cache().resolve(Path.of("agent", version, platform, executable(platform)));
        if (Files.isExecutable(binary)) return binary;
        try {
            Agent.warn("downloading the agent " + version + " for " + platform + " (once, into " + binary.getParent() + ")");
            byte[] tarball = fetch(String.format(REGISTRY, platform, version));
            if (!integrity.equals("sha512-" + Base64.getEncoder().encodeToString(sha512(tarball)))) {
                Agent.warn("the downloaded agent didn't match its checksum: not used");
                return null;
            }
            byte[] file = entry(tarball, "package/bin/" + executable(platform));
            if (file == null) return null;
            Files.createDirectories(binary.getParent());
            Path partial = Files.createTempFile(binary.getParent(), "tracepanel-agent", ".part");
            Files.write(partial, file);
            if (!partial.toFile().setExecutable(true)) Agent.warn("couldn't make " + partial + " executable");
            // Another process may download it too: the last whole file wins, never a half-written one.
            Files.move(partial, binary, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return binary;
        } catch (IOException | RuntimeException e) {
            Agent.warn("the agent couldn't be downloaded: " + e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
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

    private static byte[] fetch(String url) throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(TIMEOUT).build();
        HttpResponse<byte[]> response = http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " from " + url);
        return response.body();
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
