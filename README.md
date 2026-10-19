# NightCache

Caches RAM et persistance SQLite locale pour plugins **Bukkit / Spigot / Paper / Folia**,
Minecraft **1.8 → 1.21.11**, **Java 21**. Package `weeno.nightcache`.

Distribué par les **releases GitHub** de ce dépôt public : chaque push sur `main` compile contre Spigot 1.8.8 **et**
Paper 1.21.11, puis publie la release `v<major>.<minor>.<build>` (marquée *latest*) avec `NightCache.jar`,
`NightCache.jar.sha256` et `version.txt`. Les plugins la téléchargent eux-mêmes au démarrage (voir *Intégration*).

Unifie les copies divergentes de `utils.cache` présentes dans les plugins Neptalia
(Items, Core, Jobs, Casino, Pass, Staff, Portals, Combat, Tokens, Boss, Mine, FactionUpgrade) :
la variante Casino sert de base, complétée par la maintenance, le `SnapshotWriter`, `JsonRow` et le diagnostic.

## Pourquoi « léger » et « zéro fuite »

- **Une seule dépendance embarquée** : Caffeine 3.1.8, relocalisé dans la lib. Plus de fastutil :
  `LongObjectCache` (tableaux primitifs + LRU par indices), `ObjectLongCache` et `Leaderboard`
  (cellules `long[]` mutées en place) sont maison.
- **Aucun thread inutile** : le thread I/O n'est créé qu'au premier store ; plus un seul thread démon
  pour les timers (janitor, leaderboards async). Les deux threads sont créés sans classloader de contexte.
- **Écrivain unique** SQLite et file d'écriture bornée à 4096. Une surcharge est refusée et journalisée,
  sans croissance mémoire.
- **Disable propre** :
  - registres statiques vidés, tâches annulées, threads arrêtés ;
  - pilote JDBC isolé désenregistré de `DriverManager` et son classloader fermé ;
  - `PlayerStates.untrack`, `DiagSnapshot.remove` et `removeAutoSave` pour les modules arrêtés à chaud.
- **Aucune donnée perdue en silence** :
  - une ligne illisible est déplacée dans une table `quarantine` au lieu d'être écrasée ;
  - `replaceAll` est protégé par `SnapshotWriter` (drapeau `ready` + tickets anti-écrasement) ;
  - aucun `catch` silencieux.

## Compatibilité

| Élément | Choix |
|---|---|
| API | compilée contre Spigot 1.8.8 et vérifiée contre Paper 1.21.11 en CI (`Bukkit`, `Plugin`, events join/quit) : aucune classe NMS ou versionnée |
| Scheduler | Bukkit `runTaskTimer`, ou `GlobalRegionScheduler` sur Folia (détection par réflexion). Les timers async n'utilisent jamais le pool async de Bukkit |
| SQLite | pilote **embarqué** si le jar contient `org.sqlite.JDBC` (sqlite-jdbc shadé **sans relocation**, chargé dans un classloader isolé) ; sinon le pilote du **serveur** (testé avec le SQLite 3.7.2 de Spigot 1.8.8) |
| Gson | celui du serveur (API compatible 2.2.4 → récentes), ou celui du plugin si le chargeur relocalise `com.google.gson` (recommandé : Gson 2.10+ gère les records) |

Avec le pilote embarqué, il faut aussi shader `slf4j-api` (dépendance de sqlite-jdbc).

## Intégration : chargement depuis `plugins/<Plugin>/libs/`

NightCache n'est **pas** shadé dans les plugins. Au `onLoad()`, avant toute classe de la lib, le chargeur du
plugin (référence : `loader/` de ce dépôt, en place dans NeptaliaCore sous `weeno.neptaliacore.core.libs`) :

1. lit `<releases>/latest/download/version.txt` ; si la version change **et garde le même major d'API**
   (`NightCache-Api` du manifeste), télécharge `download/v<version>/NightCache.jar`, vérifie le SHA-256 publié,
   la classe principale et le manifeste, puis remplace atomiquement `libs/NightCache.jar` ;
2. sans réseau, garde la copie de `libs/` ; sans copie valide, extrait la copie de secours embarquée dans le plugin
   (ressource `nightcache/NightCache.lib` : pas d'extension `.jar`, sinon le shadowJar la dépaquette) ;
3. **relocalise** le jar (ASM) avec les mêmes règles que le shadowJar du plugin, au minimum
   `weeno/nightcache/` → `weeno/<plugin>/libs/nightcache/` : l'état est statique, une copie par plugin, et deux
   plugins ne doivent jamais partager les mêmes noms de classes (le PluginClassLoader cherche d'abord chez les
   autres plugins). Le résultat est mis en cache dans `libs/.relocated/` ;
4. injecte le jar relocalisé dans le classloader du plugin (`addURL`, sinon `URLClassPath` via `Unsafe`).

Côté build (Gradle) : `compileOnly` sur le jar de la release épinglée + copie de secours dans les ressources, et
la relocation `weeno.nightcache` du shadowJar conservée pour réécrire les références du plugin.

```yaml
# config.yml du plugin
libs:
  nightcache:
    auto-update: true
    releases: "https://github.com/NightProjectS/NightCache/releases"
```

Construire en local : `mvn package -Drevision=1.0.0` (jar dans `target/NightCache.jar`) ; vérification 1.21.11 :
`mvn -Pmc-latest compile`.

## Cycle de vie

```java
@Override public void onEnable() {
    NightCache.enable(this);             // Data/<Plugin>/, caches, join/quit
    // migration héritée → stores + loadAll() → managers → bindPlayer(...)
    NightCache.loadOnlinePlayers();      // cas d'un /reload
    PersistenceManager.maintenance();    // tout à la fin, une seule fois
}

@Override public void onDisable() {
    shuttingDown = true;                       // 1
    PersistenceManager.beginShutdown();
    Bukkit.getScheduler().cancelTasks(this);   // 2
    // 3. arrêt des modules   4. sauvegarde SYNCHRONE (SnapshotWriter.writeNow, store.saveAll…)
    NightCache.disable();                      // 5. PersistenceManager.shutdown()  6. vidage RAM
}
```

## API

| Besoin | API |
|---|---|
| Store SQLite `Data/<Plugin>/<nom>.db` | `PersistenceManager.store("snake_case", KeyCodec.uuid(), GsonSerializer.of(X.class))` |
| Écriture runtime | `PersistenceManager.async(() -> store.save(k, v))` |
| Lecture à l'enable | `store.loadAll()` (redirigée vers le writer et attendue) |
| Snapshot complet | `SnapshotWriter` : `markReady()` après le chargement, `submit(rows)` sur le main thread, `writeNow(rows)` au disable |
| Données joueur | `PersistenceManager.bindPlayer(cache, store, défaut[, snapshot, same])` : lots de 128, éviction après écriture confirmée |
| Cache borné / TTL | `CacheManager.cache(CacheSpec.bounded(n, max))`, `CacheSpec.expiring(n, ttl, unit)`, `CacheSpec.named(n)…build()` |
| Clé `long` | `CacheManager.longCache(n, max, ttlMs)` + `Keys.chunk(x, z)` / `Keys.block(x, y, z)` |
| Clé de position | `Keys.loc(world, x, y, z)` → `world:x:y:z` |
| Compteur, classement, pool | `objectLongCache`, `leaderboard`, `asyncLeaderboard`, `pool` / `BoundedPool.of` |
| État joueur temporaire | `PlayerStates.track(map)` (purgé au quit), `trackRetained`, `onQuit(hook)` |
| Ligne JSON courte | `JsonRow.create().put("k", v)` / `JsonRow.SERIALIZER` |
| Diagnostic | `DiagSnapshot.gauge("manager:<nom>", () -> taille)`, `DiagSnapshot.capture()` |

## Tests

La lib a été vérifiée avec le pilote SQLite 3.7.2 de Spigot 1.8.8 :
- LRU et TTL ;
- quarantaine des lignes illisibles ;
- `SnapshotWriter` ;
- maintenance ;
- absence de thread restant après `shutdown`.
