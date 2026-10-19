package weeno.nightcache.objects;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * Entrée immuable d'un classement : la clé, son score et son rang.
 *
 * @param <K> type de la clé classée.
 */
public final class LeaderboardEntry<K> {

    private final K key;
    private final long score;
    private final int rank;

    public LeaderboardEntry(@NotNull K key, long score, int rank) {
        this.key = key;
        this.score = score;
        this.rank = rank;
    }

    /** @return une copie avec un autre rang. */
    @NotNull
    public LeaderboardEntry<K> withRank(int newRank) {
        return new LeaderboardEntry<>(key, score, newRank);
    }

    /** @return la clé classée. */
    @NotNull @Contract(pure = true) public K key() { return key; }

    /** @return le score associé. */
    @Contract(pure = true) public long score() { return score; }

    /** @return le rang (1 = meilleur). */
    @Contract(pure = true) public int rank() { return rank; }

    @NotNull
    @Override
    public String toString() {
        return "#" + rank + " " + key + " (" + score + ")";
    }
}
