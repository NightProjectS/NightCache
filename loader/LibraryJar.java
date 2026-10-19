package weeno.neptaliacore.core.libs;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Jar NightCache vérifié (value object) : version et major d'API lus dans le manifeste, empreinte SHA-256.
 * {@link #inspect} renvoie {@code null} pour un fichier absent, illisible ou qui n'est pas NightCache.
 */
record LibraryJar(@NotNull Path path, @NotNull String version, int api, @NotNull String sha256) {

    /**
     * Package d'origine au format interne, assemblé à l'exécution : le relocate du shadowJar réécrit aussi les
     * chaînes littérales, il transformerait {@code "weeno/nightcache/"} en nom déjà relocalisé.
     */
    static final String ORIGINAL = String.join("/", "weeno", "nightcache", "");
    static final String MAIN_CLASS = ORIGINAL + "NightCache.class";
    private static final int BUFFER = 64 * 1024;

    /** async-safe */
    static @Nullable LibraryJar inspect(@NotNull Path path) {
        if (!Files.isRegularFile(path)) return null;
        try (JarFile jar = new JarFile(path.toFile())) {
            if (jar.getEntry(MAIN_CLASS) == null) return null;
            Manifest mf = jar.getManifest();
            if (mf == null) return null;
            Attributes a = mf.getMainAttributes();
            String version = a.getValue("Implementation-Version");
            int api = parse(a.getValue("NightCache-Api"));
            if (version == null || version.isBlank() || api < 0) return null;
            return new LibraryJar(path, version.trim(), api, sha256(path));
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    /** async-safe; jar relocalisé : sa classe principale est bien sous {@code prefix}. */
    static @Nullable Path inspectRelocated(@NotNull Path path, @NotNull String prefix) {
        try (JarFile jar = new JarFile(path.toFile())) {
            return jar.getEntry(prefix + "NightCache.class") != null && jar.getEntry(MAIN_CLASS) == null ? path : null;
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    /** async-safe; major d'une version {@code 1.0.12}, {@code -1} si illisible. */
    static int major(@NotNull String version) {
        int dot = version.indexOf('.');
        return parse(dot < 0 ? version : version.substring(0, dot));
    }

    /**
     * async-safe; comparaison numérique {@code major.minor.build} ({@code 1.0.10 > 1.0.9}), composantes absentes à 0.
     * Une composante illisible vaut -1 : une version illisible est toujours la plus ancienne.
     */
    static int compareVersions(@NotNull String a, @NotNull String b) {
        String[] pa = a.split("\\.", -1);
        String[] pb = b.split("\\.", -1);
        for (int i = 0, n = Math.max(pa.length, pb.length); i < n; i++) {
            int x = i < pa.length ? parse(pa[i]) : 0;
            int y = i < pb.length ? parse(pb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    /** async-safe; empreinte hexadécimale en minuscules, lecture par tampon réutilisé. */
    static @NotNull String sha256(@NotNull Path path) throws IOException {
        MessageDigest md = digest();
        byte[] buf = new byte[BUFFER];
        try (InputStream in = Files.newInputStream(path)) {
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    static @NotNull MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    private static int parse(@Nullable String raw) {
        if (raw == null) return -1;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
