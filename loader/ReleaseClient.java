package weeno.neptaliacore.core.libs;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Accès aux releases GitHub publiques de NightCache ({@code <releases>/latest/download/version.txt},
 * {@code <releases>/download/v<version>/NightCache.jar(.sha256)}). Délais courts, tailles bornées, empreinte
 * vérifiée pendant le téléchargement. Appelé une fois au démarrage. main-thread-only
 */
final class ReleaseClient {

    private static final int CONNECT_TIMEOUT_MS = 4_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int MAX_TEXT = 256;
    private static final long MAX_JAR = 32L * 1024 * 1024;
    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");

    private ReleaseClient() {
    }

    /** @return la version de la release {@code latest}, au format {@code major.minor.build} */
    static @NotNull String latestVersion(@NotNull String releases) throws IOException {
        String v = text(releases + "/latest/download/version.txt");
        if (!VERSION.matcher(v).matches()) throw new IOException("version distante illisible : " + v);
        return v;
    }

    /** Télécharge le jar de {@code version} dans {@code out} ; échoue si l'empreinte ne correspond pas. */
    static void download(@NotNull String releases, @NotNull String version, @NotNull Path out) throws IOException {
        String base = releases + "/download/v" + version + "/";
        String expected = text(base + "NightCache.jar.sha256").toLowerCase(Locale.ROOT);
        if (!SHA.matcher(expected).matches()) throw new IOException("empreinte distante illisible");
        MessageDigest md = LibraryJar.digest();
        HttpURLConnection c = open(base + "NightCache.jar");
        try (InputStream in = c.getInputStream(); OutputStream os = Files.newOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            for (int n; (n = in.read(buf)) > 0; ) {
                total += n;
                if (total > MAX_JAR) throw new IOException("jar distant trop volumineux");
                md.update(buf, 0, n);
                os.write(buf, 0, n);
            }
        } finally {
            c.disconnect();
        }
        String actual = HexFormat.of().formatHex(md.digest());
        if (!actual.equals(expected)) {
            Files.deleteIfExists(out);
            throw new IOException("empreinte SHA-256 différente de celle publiée");
        }
    }

    private static @NotNull String text(@NotNull String url) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            byte[] b = in.readNBytes(MAX_TEXT);
            return new String(b, StandardCharsets.UTF_8).trim();
        } finally {
            c.disconnect();
        }
    }

    private static @NotNull HttpURLConnection open(@NotNull String url) throws IOException {
        if (!url.startsWith("https://")) throw new IOException("seules les URL https sont acceptées");
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "NightCache-Loader");
        int code = c.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            c.disconnect();
            throw new IOException("HTTP " + code + " sur " + url);
        }
        return c;
    }
}
