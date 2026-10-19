package weeno.neptaliacore.core.libs;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

/**
 * Relocalisation d'un jar (équivalent runtime des {@code relocate} de shadowJar) : chaque classe et chaque chemin
 * sous un préfixe {@code from} passe sous {@code to}, références comprises (ASM, frames conservées sans recalcul).
 * Les signatures {@code META-INF/*.SF|DSA|RSA|EC}, devenues invalides, sont retirées. async-safe
 */
final class JarRelocator {

    /** Règle {@code from -> to} au format interne JVM, préfixes terminés par {@code /}. */
    record Rule(@NotNull String from, @NotNull String to) {

        Rule {
            if (!from.endsWith("/") || !to.endsWith("/")) throw new IllegalArgumentException("préfixe sans / final : " + from + " -> " + to);
        }
    }

    private JarRelocator() {
    }

    /** La première règle dont le préfixe correspond s'applique. */
    static void relocate(@NotNull Path source, @NotNull Path target, @NotNull @Unmodifiable List<Rule> rules) throws IOException {
        Remapper remapper = new PrefixRemapper(rules);
        try (JarFile in = new JarFile(source.toFile()); OutputStream os = Files.newOutputStream(target);
             JarOutputStream out = new JarOutputStream(os)) {
            Enumeration<JarEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                String name = e.getName();
                if (e.isDirectory() || isSignature(name)) continue;
                byte[] data;
                try (InputStream is = in.getInputStream(e)) {
                    data = is.readAllBytes();
                }
                if (name.endsWith(".class")) {
                    ClassReader reader = new ClassReader(data);
                    ClassWriter writer = new ClassWriter(0);
                    reader.accept(new ClassRemapper(writer, remapper), 0);
                    data = writer.toByteArray();
                    name = remapper.map(name.substring(0, name.length() - 6)) + ".class";
                } else {
                    name = remapper.map(name);
                }
                ZipEntry entry = new ZipEntry(name);
                entry.setTime(e.getTime());
                out.putNextEntry(entry);
                out.write(data);
                out.closeEntry();
            }
        }
    }

    private static boolean isSignature(@NotNull String name) {
        if (!name.startsWith("META-INF/")) return false;
        return name.endsWith(".SF") || name.endsWith(".DSA") || name.endsWith(".RSA") || name.endsWith(".EC");
    }

    /** Remplace le premier préfixe connu d'un nom interne ; un nom sans préfixe connu est rendu tel quel. */
    private static final class PrefixRemapper extends Remapper {

        private final List<Rule> rules;

        PrefixRemapper(@NotNull List<Rule> rules) {
            this.rules = List.copyOf(rules);
        }

        @Override
        public String map(String internalName) {
            for (int i = 0, n = rules.size(); i < n; i++) {
                Rule r = rules.get(i);
                if (internalName.startsWith(r.from())) return r.to() + internalName.substring(r.from().length());
            }
            return internalName;
        }
    }
}
