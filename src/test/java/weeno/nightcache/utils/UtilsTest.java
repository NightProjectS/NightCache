package weeno.nightcache.utils;

import org.junit.jupiter.api.Test;
import weeno.nightcache.diag.DiagSnapshot;

import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Clés packées, codecs, lignes JSON (gson 2.2.4 des serveurs 1.8) et jauges du diagnostic. */
class UtilsTest {

    @Test
    void chunkAndBlockKeysRoundTripOnRandomCoordinates() {
        Random r = new Random(5);
        for (int i = 0; i < 100_000; i++) {
            int x = r.nextInt(), z = r.nextInt();
            long c = Keys.chunk(x, z);
            assertEquals(x, Keys.chunkX(c));
            assertEquals(z, Keys.chunkZ(c));
            int bx = r.nextInt(67_108_864) - 33_554_432, by = r.nextInt(4_096), bz = r.nextInt(67_108_864) - 33_554_432;
            long b = Keys.block(bx, by, bz);
            assertEquals(bx, Keys.blockX(b));
            assertEquals(by, Keys.blockY(b));
            assertEquals(bz, Keys.blockZ(b));
        }
        assertEquals(Keys.chunk(Integer.MIN_VALUE, Integer.MAX_VALUE), Keys.chunk(Integer.MIN_VALUE, Integer.MAX_VALUE));
        assEqualsDistinct(Keys.chunk(0, -1), Keys.chunk(-1, 0));
        assertEquals("monde:-1:64:2", Keys.loc("monde", -1, 64, 2));
    }

    private static void assEqualsDistinct(long a, long b) {
        assertTrue(a != b, "clés distinctes");
    }

    @Test
    void keyCodecsRoundTripAndRejectGarbage() {
        UUID id = UUID.randomUUID();
        assertEquals(id, KeyCodec.uuid().decode(KeyCodec.uuid().encode(id)));
        assertEquals(Long.MIN_VALUE, KeyCodec.longKey().decode(KeyCodec.longKey().encode(Long.MIN_VALUE)));
        assertEquals("é 🎉", KeyCodec.string().decode("é 🎉"));
        assertThrows(IllegalArgumentException.class, () -> KeyCodec.uuid().decode("pas-un-uuid"));
        assertThrows(NumberFormatException.class, () -> KeyCodec.longKey().decode("12a"));
    }

    @Test
    void jsonRowIsCompactStableAndTolerant() {
        JsonRow r = JsonRow.create().put("s", "é\"<>&").put("n", 42L).put("b", false).put("u", (UUID) null)
                .put("x", (String) null).put("d", 1.5);
        String raw = r.toString();
        assertEquals("{\"s\":\"é\\\"\u003c\u003e\u0026\",\"n\":42,\"b\":false,\"d\":1.5}", raw);
        JsonRow back = JsonRow.parse(raw);
        assertEquals("é\"<>&", back.getString("s"));
        assertEquals(42, back.i("n"));
        assertFalse(back.has("x"));
        assertEquals(7L, back.getLong("absent", 7L));
        assertEquals(7L, back.getLong("s", 7L), "mauvais type : valeur de repli");
        assertNull(back.getUuid("s"));
        assertEquals("défaut", back.getString("absent", "défaut"));
        for (String bad : new String[]{"", "   ", "null", "[]", "42", "\"txt\"", "{", "{\"a\":", "\u0000", "{}}"}) {
            JsonRow p = JsonRow.parse(bad);
            assertTrue(p == null || p.toString().equals("{}"), "texte non objet : " + bad);
        }
        assertEquals(r.toString(), JsonRow.SERIALIZER.deserialize(JsonRow.SERIALIZER.serialize(r)).toString());
    }

    @Test
    void gsonSerializerRoundTripsAndReturnsNullOnGarbage() {
        GsonSerializer<Box> s = GsonSerializer.of(Box.class);
        Box b = new Box();
        b.name = "<b>é</b>";
        b.values = new long[]{Long.MAX_VALUE, -1};
        Box back = s.deserialize(s.serialize(b));
        assertEquals(b.name, back.name);
        assertEquals(Long.MAX_VALUE, back.values[0]);
        assertTrue(s.serialize(b).contains("<b>"), "pas d'échappement HTML (taille disque)");
        assertNull(s.deserialize("{pas du json"));
        assertNull(s.deserialize("{\"values\":\"texte\"}"));
    }

    @Test
    void gaugesNeverBreakTheDiagnostic() {
        DiagSnapshot.clearGauges();
        DiagSnapshot.gauge("b:ok", () -> 3);
        DiagSnapshot.gauge("a:boom", () -> { throw new IllegalStateException("arrêt"); });
        List<DiagSnapshot.Gauge> g = DiagSnapshot.read();
        assertEquals(List.of(new DiagSnapshot.Gauge("a:boom", -1), new DiagSnapshot.Gauge("b:ok", 3)), g);
        DiagSnapshot.remove("b:ok");
        assertEquals(1, DiagSnapshot.size());
        DiagSnapshot.clear();
        assertEquals(0, DiagSnapshot.size());
    }

    static final class Box {
        String name;
        long[] values;
    }
}
