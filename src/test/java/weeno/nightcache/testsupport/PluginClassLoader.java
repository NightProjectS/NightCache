package weeno.nightcache.testsupport;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.Enumeration;

/**
 * Classloader de plugin simulé : comme une copie relocalisée de NightCache dans un plugin, les classes
 * {@code weeno.nightcache.*} (et Caffeine, embarqué relocalisé) — plus, au choix, le pilote {@code org.sqlite.*}
 * embarqué — sont chargées par CE loader, le reste (Bukkit, Gson, JDK) par le parent.
 */
public final class PluginClassLoader extends URLClassLoader {

    static { registerAsParallelCapable(); }

    private final boolean ownSqlite;

    public PluginClassLoader(URL[] urls, ClassLoader parent, boolean ownSqlite) {
        super(urls, parent);
        this.ownSqlite = ownSqlite;
    }

    private boolean own(String name) {
        return name.startsWith("weeno.nightcache.") && !name.startsWith("weeno.nightcache.testsupport.")
                && !name.endsWith("Test") || name.startsWith("com.github.benmanes.caffeine.")
                || ownSqlite && name.startsWith("org.sqlite.");
    }

    private boolean ownResource(String path) {
        return ownSqlite && path.startsWith("org/sqlite/");
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!own(name)) return super.loadClass(name, resolve);
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) c = findClass(name);
            if (resolve) resolveClass(c);
            return c;
        }
    }

    @Override
    public URL getResource(String name) {
        if (ownResource(name)) return findResource(name);
        return super.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        if (ownResource(name)) {
            URL u = findResource(name);
            return u == null ? Collections.emptyEnumeration() : Collections.enumeration(Collections.singletonList(u));
        }
        return super.getResources(name);
    }
}
