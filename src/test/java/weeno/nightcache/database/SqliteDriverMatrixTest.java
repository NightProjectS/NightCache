package weeno.nightcache.database;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Garde-fou de la matrice : chaque passe des tests de stockage tourne bien sur le pilote annoncé par le pom. */
@Tag("sqlite")
class SqliteDriverMatrixTest {

    @Test
    void runsOnTheDriverDeclaredByThePom() throws Exception {
        String expected = System.getProperty("nightcache.sqlite");
        assumeTrue(expected != null, "lancé hors Maven");
        Driver d = (Driver) Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance();
        assertEquals(expected, d.getMajorVersion() + "." + d.getMinorVersion());
    }
}
