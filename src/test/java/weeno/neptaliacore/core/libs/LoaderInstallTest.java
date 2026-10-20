package weeno.neptaliacore.core.libs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Chargeur de référence ({@code loader/}, copié dans les plugins) : un remplacement de {@code libs/NightCache.jar} qui
 * échoue alors qu'un ancien jar valide est en place ne doit jamais être annoncé comme réussi (sinon
 * {@code NightCache.version} indiquerait la nouvelle version et la mise à jour ne serait plus jamais retentée).
 */
class LoaderInstallTest {

    @TempDir
    Path dir;

    @Test
    void replacesTheJarAtomically() throws Exception {
        Path old = jar(dir.resolve("NightCache.jar"), "1.0.3");
        Path part = jar(dir.resolve("new.part"), "1.0.4");
        assertTrue(NightCacheLibrary.install(part, old));
        assertFalse(Files.exists(part));
        LibraryJar now = LibraryJar.inspect(old);
        assertNotNull(now);
        assertEquals("1.0.4", now.version());
    }

    @Test
    void failedReplacementOverAnOldValidJarIsReportedAndKeepsIt() throws Exception {
        Path target = jar(dir.resolve("NightCache.jar"), "1.0.3");
        byte[] before = Files.readAllBytes(target);
        Path part = jar(dir.resolve("new.part"), "1.0.4");
        Runnable unlock = lock(target);
        try {
            assertFalse(NightCacheLibrary.install(part, target), "remplacement raté annoncé comme réussi");
        } finally {
            unlock.run();
        }
        assertArrayEquals(before, Files.readAllBytes(target), "l'ancien jar valide reste intact");
        assertEquals("1.0.3", LibraryJar.inspect(target).version());
    }

    @Test
    void identicalJarInstalledConcurrentlyCountsAsInstalled() throws Exception {
        Path target = jar(dir.resolve("NightCache.jar"), "1.0.4");
        Path part = dir.resolve("same.part");
        Files.copy(target, part);
        Runnable unlock = lock(target);
        try {
            assertTrue(NightCacheLibrary.install(part, target), "autre serveur du même dossier : même contenu");
        } finally {
            unlock.run();
        }
    }

    @Test
    void failureWithoutAValidJarInPlaceThrows() throws Exception {
        Path target = dir.resolve("NightCache.jar");
        Files.createDirectories(target.resolve("occupé")); // un dossier non vide à la place du jar
        Path part = jar(dir.resolve("new.part"), "1.0.4");
        assertThrows(IOException.class, () -> NightCacheLibrary.install(part, target));
    }

    /** Rend le remplacement de {@code target} impossible (lecture seule sous Windows, dossier figé sous POSIX). */
    private Runnable lock(Path target) throws IOException {
        DosFileAttributeView dos = Files.getFileAttributeView(target, DosFileAttributeView.class);
        PosixFileAttributeView posix = Files.getFileAttributeView(dir, PosixFileAttributeView.class);
        Runnable unlock;
        if (posix != null) {
            var perms = posix.readAttributes().permissions();
            posix.setPermissions(PosixFilePermissions.fromString("r-xr-xr-x"));
            unlock = () -> {
                try {
                    posix.setPermissions(perms);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            };
        } else {
            assumeTrue(dos != null, "système de fichiers sans verrou simulable");
            dos.setReadOnly(true);
            unlock = () -> {
                try {
                    dos.setReadOnly(false);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            };
        }
        // Précondition : le verrou tient vraiment (root ignore les permissions POSIX).
        Path probe = Files.createTempFile("probe", ".jar");
        boolean blocked;
        try {
            Files.copy(probe, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            blocked = false;
        } catch (IOException e) {
            blocked = true;
        } finally {
            Files.deleteIfExists(probe);
        }
        if (!blocked) {
            unlock.run();
            assumeTrue(false, "verrou de fichier non applicable ici");
        }
        return unlock;
    }

    private static Path jar(Path path, String version) throws IOException {
        Manifest mf = new Manifest();
        Attributes a = mf.getMainAttributes();
        a.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        a.putValue("Implementation-Version", version);
        a.putValue("NightCache-Api", "1");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(path), mf)) {
            out.putNextEntry(new JarEntry(LibraryJar.MAIN_CLASS));
            out.write(version.getBytes());
            out.closeEntry();
        }
        return path;
    }
}
