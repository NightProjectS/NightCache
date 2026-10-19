package weeno.neptaliacore.core.libs;

import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collection;

/**
 * Ajout d'un jar au classloader d'un plugin ({@code PluginClassLoader} est un {@link URLClassLoader}, de la 1.8 à
 * la 1.21). {@code addURL} est protégé : appel direct s'il est accessible (Java 8, {@code --add-opens}), sinon
 * mise à jour des deux files de {@code URLClassPath} via {@code sun.misc.Unsafe}, méthode éprouvée de LuckPerms et
 * libby sur Java 9 à 21. main-thread-only
 */
final class ClassPathInjector {

    private ClassPathInjector() {
    }

    static void add(@NotNull ClassLoader loader, @NotNull URL url) {
        if (!(loader instanceof URLClassLoader ucl)) {
            throw new IllegalStateException("classloader non supporté : " + loader.getClass().getName());
        }
        try {
            Method addUrl = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            addUrl.setAccessible(true);
            addUrl.invoke(ucl, url);
            return;
        } catch (InaccessibleObjectException | ReflectiveOperationException e) {
            // Java 9+ sans --add-opens java.base/java.net : voie Unsafe ci-dessous.
        }
        try {
            unsafeAdd(ucl, url);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("impossible d'ajouter " + url + " au classloader du plugin", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void unsafeAdd(@NotNull URLClassLoader loader, @NotNull URL url) throws ReflectiveOperationException {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeType.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        Method offset = unsafeType.getMethod("objectFieldOffset", Field.class);
        Method get = unsafeType.getMethod("getObject", Object.class, long.class);

        Object ucp = get.invoke(unsafe, loader, offset.invoke(unsafe, URLClassLoader.class.getDeclaredField("ucp")));
        Class<?> ucpType = ucp.getClass();
        Collection<URL> unopened = (Collection<URL>) get.invoke(unsafe, ucp, offset.invoke(unsafe, unopenedField(ucpType)));
        Collection<URL> path = (Collection<URL>) get.invoke(unsafe, ucp, offset.invoke(unsafe, ucpType.getDeclaredField("path")));
        // Même verrouillage que URLClassPath.addURL (méthode synchronized, puis la file).
        synchronized (ucp) {
            synchronized (unopened) {
                if (path.contains(url)) return;
                unopened.add(url);
                path.add(url);
            }
        }
    }

    /** {@code unopenedUrls} depuis Java 17 environ, {@code unopenedURLs} avant. */
    private static @NotNull Field unopenedField(@NotNull Class<?> ucpType) throws NoSuchFieldException {
        try {
            return ucpType.getDeclaredField("unopenedUrls");
        } catch (NoSuchFieldException e) {
            return ucpType.getDeclaredField("unopenedURLs");
        }
    }
}
