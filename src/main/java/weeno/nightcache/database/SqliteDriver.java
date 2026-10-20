package weeno.nightcache.database;

import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Enumeration;
import java.util.Properties;

/**
 * Pont chargé <b>uniquement</b> dans le classloader isolé de {@link SqliteConnections} : instancie
 * le {@code org.sqlite.JDBC} embarqué par le plugin (noms JNI intacts) sans passer par le
 * pilote, souvent ancien, du serveur. Aucune dépendance de compilation à sqlite-jdbc.
 */
public final class SqliteDriver {

    private SqliteDriver() {}

    /** Thread: async-safe ; l'appelant possède la connexion retournée. */
    public static @NotNull Connection open(@NotNull String url) throws Exception {
        ClassLoader own = SqliteDriver.class.getClassLoader();
        Driver driver = (Driver) Class.forName("org.sqlite.JDBC", true, own).getDeclaredConstructor().newInstance();
        // org.sqlite.JDBC s'auto-enregistre dans DriverManager : on le retire, sinon DriverManager
        // retiendrait ce classloader (et tout le plugin) après le disable → fuite mémoire.
        Enumeration<Driver> registered = DriverManager.getDrivers();
        while (registered.hasMoreElements()) {
            Driver d = registered.nextElement();
            if (d.getClass().getClassLoader() == own) DriverManager.deregisterDriver(d);
        }
        Connection c = driver.connect(url, new Properties());
        if (c == null) throw new SQLException("URL SQLite non reconnue : " + url);
        return c;
    }

    /** Retire de {@code DriverManager} les pilotes chargés par ce classloader isolé (appelé au shutdown). */
    public static void deregister() throws SQLException {
        ClassLoader own = SqliteDriver.class.getClassLoader();
        Enumeration<Driver> registered = DriverManager.getDrivers();
        while (registered.hasMoreElements()) {
            Driver d = registered.nextElement();
            if (d.getClass().getClassLoader() == own) DriverManager.deregisterDriver(d);
        }
    }
}
