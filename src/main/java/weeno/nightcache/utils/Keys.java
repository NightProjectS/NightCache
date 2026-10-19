package weeno.nightcache.utils;

import org.jetbrains.annotations.Contract;

/**
 * Packing de coordonnées Minecraft en clés {@code long} (zéro allocation),
 * pour indexer claims/lands/blocs/chunks via {@code LongObjectCache} sans objet clé.
 */
public final class Keys {

    private Keys() {}

    /** Empaquette une coordonnée de chunk {@code (x, z)} en un {@code long} unique. */
    @Contract(pure = true)
    public static long chunk(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /** @return coordonnée X du chunk depuis une clé {@link #chunk(int, int)}. */
    @Contract(pure = true)
    public static int chunkX(long key) { return (int) (key >> 32); }

    /** @return coordonnée Z du chunk depuis une clé {@link #chunk(int, int)}. */
    @Contract(pure = true)
    public static int chunkZ(long key) { return (int) key; }

    /**
     * Empaquette une position de bloc {@code (x, y, z)} (schéma vanilla : 26 bits X, 12 bits Y, 26 bits Z).
     * X/Z supportent {@code ±33,5M}, Y {@code 0..4095}.
     */
    @Contract(pure = true)
    public static long block(int x, int y, int z) {
        return ((x & 0x3FFFFFFL) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFFL);
    }

    /** @return coordonnée X du bloc (sign-extended) depuis une clé {@link #block(int, int, int)}. */
    @Contract(pure = true)
    public static int blockX(long key) { return (int) (key >> 38); }

    /** @return coordonnée Y du bloc depuis une clé {@link #block(int, int, int)}. */
    @Contract(pure = true)
    public static int blockY(long key) { return (int) ((key >> 26) & 0xFFF); }

    /** @return coordonnée Z du bloc (sign-extended) depuis une clé {@link #block(int, int, int)}. */
    @Contract(pure = true)
    public static int blockZ(long key) { return (int) (key << 38 >> 38); }

    /**
     * Clé de ligne SQLite d'une position de bloc : {@code world:x:y:z}. Format unique pour
     * tous les plugins — ne pas le réinventer.
     */
    @Contract(pure = true)
    public static String loc(String world, int x, int y, int z) {
        return world + ':' + x + ':' + y + ':' + z;
    }
}
