package weeno.nightcache;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contrat binaire de l'API 1.x : les plugins compilés contre 1.0.1 chargent la dernière 1.x au démarrage. La liste
 * figée ({@code api/nightcache-api-1.txt}, signatures effacées de 1.0.1) doit rester entièrement présente : aucune
 * classe, méthode, constructeur ou champ public supprimé, renommé, déplacé ou devenu (non) statique. Les ajouts sont
 * permis, sauf une méthode abstraite de plus dans une interface publique (casserait les implémentations des plugins).
 */
class ApiContractTest {

    private static final String FROZEN = "/api/nightcache-api-1.txt";

    @Test
    void everyFrozenSignatureIsStillPresent() throws Exception {
        Set<String> frozen = frozen();
        Set<String> current = dump();
        List<String> missing = new ArrayList<>();
        for (String line : frozen) if (!current.contains(line)) missing.add(line);
        assertTrue(missing.isEmpty(), "signatures publiques supprimées ou modifiées :\n" + String.join("\n", missing));
        assertTrue(frozen.size() > 300, "liste figée tronquée");
    }

    @Test
    void publicInterfacesGainNoAbstractMethod() throws Exception {
        Set<String> frozen = frozen();
        for (String line : frozen) {
            if (!line.startsWith("type ") || !line.endsWith("interface")) continue;
            Class<?> type = Class.forName(line.split(" ")[1]);
            for (Method m : type.getDeclaredMethods()) {
                if (!Modifier.isAbstract(m.getModifiers())) continue;
                String sig = "method " + type.getName() + "#" + m.getName() + "(" + params(m.getParameterTypes()) + ")"
                        + m.getReturnType().getTypeName();
                assertTrue(frozen.contains(sig), "nouvelle méthode abstraite dans une interface publique : " + sig);
            }
        }
    }

    @Test
    void manifestApiMajorStaysOne() throws IOException {
        // Le chargeur des plugins refuse un jar d'un autre major : le pom doit garder nightcache.api = 1.
        String pom = Files.readString(Paths.get(System.getProperty("nightcache.basedir", "."), "pom.xml"), StandardCharsets.UTF_8);
        assertTrue(pom.contains("<nightcache.api>1</nightcache.api>"), "major d'API modifié");
    }

    private static Set<String> frozen() throws IOException {
        Set<String> out = new TreeSet<>();
        try (InputStream in = ApiContractTest.class.getResourceAsStream(FROZEN)) {
            assertNotNull(in, FROZEN);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String l; (l = r.readLine()) != null; ) if (!l.isBlank()) out.add(l.strip());
        }
        return out;
    }

    /** Même format que la liste figée, calculé sur les classes compilées. */
    static Set<String> dump() throws IOException, URISyntaxException, ClassNotFoundException {
        Path root = Paths.get(NightCache.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Set<String> out = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String f = root.relativize(p).toString().replace(p.getFileSystem().getSeparator(), "/");
                if (!f.endsWith(".class")) continue;
                Class<?> c = Class.forName(f.substring(0, f.length() - 6).replace('/', '.'), false,
                        ApiContractTest.class.getClassLoader());
                if (!isApi(c)) continue;
                out.add("type " + c.getName() + " " + Modifier.toString(c.getModifiers()
                        & (Modifier.ABSTRACT | Modifier.FINAL | Modifier.INTERFACE | Modifier.STATIC)));
                for (Constructor<?> k : c.getDeclaredConstructors()) {
                    if (visible(k.getModifiers())) out.add("ctor " + c.getName() + "(" + params(k.getParameterTypes()) + ")");
                }
                for (Method m : c.getDeclaredMethods()) {
                    if (!visible(m.getModifiers()) || m.isSynthetic() || m.isBridge()) continue;
                    out.add("method " + c.getName() + "#" + (Modifier.isStatic(m.getModifiers()) ? "static " : "")
                            + m.getName() + "(" + params(m.getParameterTypes()) + ")" + m.getReturnType().getTypeName());
                }
                for (Field fl : c.getDeclaredFields()) {
                    if (!visible(fl.getModifiers())) continue;
                    out.add("field " + c.getName() + "#" + (Modifier.isStatic(fl.getModifiers()) ? "static " : "")
                            + fl.getName() + ":" + fl.getType().getTypeName());
                }
            }
        }
        return out;
    }

    private static boolean visible(int m) {
        return Modifier.isPublic(m) || Modifier.isProtected(m);
    }

    private static boolean isApi(Class<?> c) {
        for (Class<?> x = c; x != null; x = x.getDeclaringClass()) {
            if (!Modifier.isPublic(x.getModifiers()) || x.isAnonymousClass() || x.isLocalClass()) return false;
        }
        return true;
    }

    private static String params(Class<?>[] types) {
        StringJoiner j = new StringJoiner(",");
        for (Class<?> t : types) j.add(t.getTypeName());
        return j.toString();
    }
}
