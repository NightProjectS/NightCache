# NightCache

Caches RAM et persistance SQLite pour plugins Bukkit / Spigot / Paper / Folia, Minecraft 1.8 → 1.21.11, Java 21.

## Fonctionnement

- Chaque push sur `main` publie une release `v<major>.<minor>.<build>` : `NightCache.jar`, `NightCache.jar.sha256`, `version.txt`.
- Le plugin n'embarque pas NightCache dans ses classes. Au démarrage (`onLoad`), il :
  1. télécharge la dernière release dans `plugins/<Plugin>/libs/` si elle est plus récente (même version majeure, SHA-256 vérifié) ;
  2. sinon garde la copie de `libs/`, sinon utilise la copie de secours livrée dans son jar ;
  3. relocalise la lib sous son propre package (l'état de NightCache est statique : une copie par plugin) ;
  4. l'ajoute à son classloader.

## Embarquer dans un plugin

1. Copier les 5 classes de [`loader/`](loader) dans le plugin (ex. `<racine>/core/libs/`) et adapter le `package`.
2. Dans `NightCacheLibrary`, aligner `RELOCATED` et `RULES` sur les `relocate` du shadowJar :
   `weeno/nightcache/` → `<racine>/libs/nightcache/`, et `com/google/gson/` → le Gson shadé du plugin s'il en embarque un.
3. `build.gradle.kts` :
   ```kotlin
   // jar de la release épinglée (téléchargé une fois, SHA-256 vérifié : voir NeptaliaCore)
   compileOnly(files(nightCacheJar))
   testImplementation(files(nightCacheJar))
   implementation("org.ow2.asm:asm:9.9.1")
   implementation("org.ow2.asm:asm-commons:9.9.1")

   tasks.processResources {
       // copie de secours ; pas d'extension .jar, sinon le shadowJar la dépaquette
       from(nightCacheJar) { into("nightcache"); rename { "NightCache.lib" } }
   }
   tasks.shadowJar {
       relocate("org.objectweb.asm", "<racine>.libs.asm")
       relocate("weeno.nightcache", "<racine>.libs.nightcache")
   }
   ```
4. Plugin principal :
   ```java
   @Override public void onLoad() {
       try { NightCacheLibrary.load(this); loaded = true; }
       catch (IllegalStateException e) { getLogger().severe(e.getMessage()); }
   }
   @Override public void onEnable() {
       if (!loaded) { getServer().getPluginManager().disablePlugin(this); return; }
       NightCache.enable(this);
   }
   @Override public void onDisable() {
       if (loaded) NightCache.disable();
   }
   ```
5. `config.yml` :
   ```yaml
   libs:
     nightcache:
       auto-update: true
       releases: "https://github.com/NightProjectS/NightCache/releases"
   ```

Exemple complet : NeptaliaCore (`weeno.neptaliacore.core.libs`).

## Build

`mvn verify -Drevision=1.0.0` → `target/NightCache.jar` (tests sur SQLite 3.7.2 puis 3.45, chargeur `loader/` compris).
Compatibilité binaire : jar de la dernière release dans `target/baseline/NightCache.jar`, puis `-Djapicmp.skip=false`.
Vérification Paper 1.21.11 : `mvn -Pmc-latest compile`.
