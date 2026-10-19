# Chargeur de référence (à copier dans chaque plugin)

Copie du chargeur en service dans NeptaliaCore (`weeno.neptaliacore.core.libs`). Il n'est pas compilé avec la lib.

Pour un autre plugin :
1. copier les 5 fichiers dans un package du plugin et adapter `package` ;
2. dans `NightCacheLibrary`, adapter `RELOCATED` et les `RULES` aux relocations du shadowJar du plugin
   (`weeno/nightcache/` vers le package du plugin, et `com/google/gson/` vers son Gson shadé s'il en embarque un) ;
3. build : dépendances `org.ow2.asm:asm` et `asm-commons` (relocalisées), `compileOnly` sur `NightCache.jar`
   de la release épinglée, copie de secours dans les ressources sous `nightcache/NightCache.lib` ;
4. appeler `NightCacheLibrary.load(this)` en premier dans `onLoad()`, et ne pas activer le plugin si l'appel échoue.
