package weeno.nightcache;

import weeno.nightcache.database.SqliteConnections;
import weeno.nightcache.database.SqliteStore;
import weeno.nightcache.interfaces.Serializer;
import weeno.nightcache.save.PersistenceManager;
import weeno.nightcache.utils.KeyCodec;

import java.io.File;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Exécuté dans un classloader de plugin simulé (voir {@code PluginDriverSelectionTest}) : ouvre un store et renvoie
 * la version du pilote SQLite réellement utilisé, puis ferme tout.
 */
public final class DriverScenario implements Function<String, String> {

    private static Object isolated() throws ReflectiveOperationException {
        Field f = SqliteConnections.class.getDeclaredField("isolated");
        f.setAccessible(true);
        return f.get(null);
    }

    @Override
    public String apply(String dir) {
        PersistenceManager.initializeWriter(new File(dir), Logger.getLogger("DriverScenario"));
        try {
            SqliteStore<String, String> s = PersistenceManager.store("pilote", KeyCodec.string(), new Serializer<>() {
                @Override public String serialize(String value) { return value; }
                @Override public String deserialize(String data) { return data; }
            });
            s.save("k", "v");
            if (!"v".equals(s.load("k"))) throw new IllegalStateException("relecture");
            return PersistenceManager.onWriter("pilote", "version", () -> {
                Field f = SqliteStore.class.getDeclaredField("connection");
                f.setAccessible(true);
                Connection c = (Connection) f.get(s);
                return c.getMetaData().getDriverVersion() + (isolated() != null ? "|embarqué" : "|serveur");
            });
        } finally {
            PersistenceManager.shutdown(5_000);
        }
    }
}
