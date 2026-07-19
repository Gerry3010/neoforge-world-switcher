# Changelog
All notable changes to World Switcher will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.4.0] - 2026-07-19
### Added
- **Command-Hooks** (`enableCommandHooks`, Default an): Admins können in
  `serverconfig/worldswitcher-hooks.json` pro Welt und global Befehlslisten hinterlegen, die
  automatisch bei drei Welt-Events laufen — `firstPlayerJoin` (Welt geht 0 → 1 Spieler),
  `lastPlayerLeave` (1 → 0) und `playerMoved` (ein Spieler wechselt die Welt). In jedem Befehl
  werden `{{worldName}}`, `{{worldId}}`, `{{playerName}}` und `{{playerUuid}}` ersetzt. Welt-Identität
  folgt der World-Switcher-Gruppierung (Overworld/Nether/End = eine Welt `default`, jede verwaltete
  Welt eigen; ein Overworld→Nether-Portal zählt nicht als Wechsel). Globale Hooks laufen vor den
  Welt-Hooks. Jeder Hook läuft wahlweise als Server (`as: server`, OP 4, Ausgabe unterdrückt, an
  der Welt positioniert) oder als auslösender Spieler (`as: player`); ohne Angabe greift
  `hookDefaultRunAs` (Default `server`). Fehlerhafte Befehle werden geloggt und unterbrechen den
  Wechsel/Login/Logout nie. Eine kommentierte Beispiel-Datei wird beim ersten Start erzeugt; mit
  `/wsc hooks reload` lässt sich die Datei live neu laden (`/wsc hooks status` zeigt die Zählung).
- **Klickbare Switch-Ankündigung** (`announceSwitches`, Default an): wechselt ein Spieler die
  Welt (`/ws` oder `/wsc tp`), sehen alle anderen Online-Spieler eine Chat-Meldung „<Spieler>
  switched to <Welt>" — der Weltname ist ein klickbarer `/ws <welt>`-Link, sodass sie mit einem
  Klick nachjoinen können. Über `announceSwitches = false` abschaltbar.
- **Geteiltes Inventar pro Welt** (`/wsc shareinventory <world> [true|false]`): eine Welt kann
  als „keep-inventory" markiert werden — sie nutzt dann die `default`-Inventargruppe statt einer
  eigenen, d. h. Spieler behalten beim Betreten ihre Default-Welt-Items (kein separates
  Welt-Inventar). Nur der Spielerzustand wird geteilt — Gamerules, Zeit, Wetter und Difficulty
  bleiben pro Welt. Umschalten während Spieler drin sind gruppiert sie an Ort und Stelle um (der
  vorherige Zustand bleibt für ein Zurückschalten erhalten; modded Client-HUDs wie Curios können
  bis zum nächsten Relog nachhinken). `/wsc list` markiert solche Welten mit `keep-inv`, `/wsc
  info` zeigt den Status. (Erster Schritt Richtung frei konfigurierbarer Weltgruppen.)
### Changed
- `persistentDataExcludes` ist jetzt standardmäßig leer (die `WaystonesData`-Ausnahme brachte
  in der Praxis nichts — Waystones verhalten sich ohnehin pro Welt).

## [1.2.0] - 2026-07-03
### Added
- **Modded Spielerstatus pro Welt** (Teil von `separateInventories`): drei Mechanismen decken
  alles ab, was Mods am Spieler speichern — ohne Mod-Abhängigkeiten:
  - **NeoForge-Data-Attachments** (`swapModAttachments`, Default an): z. B. **Curios-Slots**
    (Charm/Ring/Necklace/… inkl. Elytra-Slot und getragenem Toolbelt) wechseln pro Welt.
    Ausnahmen über `attachmentExcludes`.
  - **Persistent-Data** (`swapPersistentData`, Default an): Spieler-NBT unter `NeoForgeData`;
    einzelne Keys per `persistentDataExcludes` ausnehmbar (Default: `WaystonesData`).
  - **Tough As Nails** (`swapToughAsNails`, Default an): Durst + Temperatur pro Welt
    (per Reflection über die TAN-API; ohne TAN wirkungslos).
- **Difficulty pro Welt** (`perWorldDifficulty`, Default an): `/difficulty` wirkt in einer
  verwalteten Welt nur auf diese (inkl. Peaceful-Despawn, Mob-Spawnregeln, Regional-Difficulty);
  neu `/wsc difficulty <world> [value]`. Importe übernehmen die Difficulty aus der `level.dat`,
  der Client sieht beim Weltwechsel automatisch den richtigen Wert.
- **Sicherer Relog nach Welt-Entladung**: Wer offline in einer inzwischen entladenen/gelöschten
  Welt war, wird beim Login nicht mehr an den alten Koordinaten in der Overworld abgesetzt
  (Erstickungs-/Sturzgefahr), sondern an die letzte Default-Position bzw. den Spawn teleportiert.
### Fixed
- Modded Spielerdaten (z. B. Curios) wurden beim Weltwechsel bisher gar nicht getrennt und
  „lecken" zwischen Welten — jetzt Teil des Per-Welt-Snapshots (s. o.).

## [1.1.0] - 2026-07-03
### Added
- **Gamerules pro Welt** (`perWorldGameRules`, Default an): jede verwaltete Welt hat ihre eigenen
  Gamerules — `keepInventory`, `mobGriefing`, `randomTickSpeed` usw. wirken nur dort. Neue Welten
  starten mit einer Kopie der globalen Rules, Importe übernehmen die Rules aus ihrer `level.dat`.
  Funktioniert auch mit Mod-Rules (z. B. Serene Seasons' `doSeasonCycle`).
- **Tageszeit + Wetter pro Welt** (`perWorldTimeAndWeather`, Default an): eigene Uhr und eigenes
  Wetter je Welt — ewige Nacht, Dauerregen, eingefrorene Zeit (`doDaylightCycle false`) sind
  jetzt pro Welt möglich. Schlafen überspringt nur die Nacht der eigenen Welt. Importe übernehmen
  die Uhrzeit aus ihrer `level.dat`.
- **Kontextsensitive Vanilla-Commands**: `/gamerule`, `/time` und `/weather` wirken in einer
  verwalteten Welt nur auf diese, in den Vanilla-Dimensionen global wie bisher.
  `/execute in worldswitcher:<id> run …` targetet eine Welt von überall; neu außerdem
  `/wsc gamerule <world> [<rule> [value]]` inkl. Override-Liste (ohne Rule-Argument).
- Die drei client-seitigen Rules (`doImmediateRespawn`, `reducedDebugInfo`, `doLimitedCrafting`)
  werden bei jedem Weltwechsel/Respawn an den Client nachsynchronisiert (Vanilla sendet sie nur
  beim Login).
- Blankes `/ws` zeigt die Weltliste (klickbar, mit „you are here"-Markierung) statt eines
  Brigadier-Usage-Fehlers; blankes `/wsc` bzw. `/wsc help` zeigt eine Aktions-Übersicht.
### Changed
- `swapGamemode` ist jetzt standardmäßig **an**: der Gamemode ist Teil des Per-Welt-Status —
  beim Wechsel wird der zuletzt in der Zielwelt genutzte Modus wiederhergestellt (Erstbesuch
  behält den aktuellen). Bestehende Server behalten ihren Config-Wert.
### Fixed
- `/wsc list` zeigt jetzt auch die `default`-Welt (mit Spielerzahl) und markiert die Welt des
  Aufrufers mit „(you are here)" — vorher wirkte die Liste nach einem Tod (Respawn in der
  Overworld) wie ein kaputter Spieler-Zähler.
- Cross-World-Tod mit unterschiedlichem `keepInventory`: Vanilla droppt nach der Regel der
  Todeswelt, stellt aber nach der Regel der Respawn-Welt wieder her — bei `true`→`false` wären
  Items ersatzlos verschwunden. Die Regel der Todeswelt ist jetzt maßgeblich.

## [1.0.0] - 2026-07-02
### Added
- **`/ws <welt>`**: Weltwechsel zur Laufzeit ohne Server-Neustart (Multiverse-artig). Für alle
  Spieler erlaubt (Permission-Level konfigurierbar), Tab-Completion, `default` = Vanilla-Welten
  (Overworld/Nether/End).
- **`/wsc`-Verwaltung (OP 2+)**: `list` (klickbare Namen), `info` (Seed/Spawn/Ordner/Disk-Size),
  `create <name> [seed]`, `import <ordner> [as <name>]`, `rename`, `load`/`unload`,
  `tp <spieler> <welt>`, `delete` mit Confirm/Cancel-Buttons (30 s Timeout).
- **Welt-Import**: kopiert Weltordner aus `worlds/` (Root oder Subpfade, z. B. `"backups/alt"`)
  in den Server-Save — nur Overworld-Daten (`region`, `entities`, `poi`, `data`), Quelle bleibt
  unangetastet. Seed + Spawn werden aus der `level.dat` gelesen (auch Pre-1.16-Format), Terrain
  generiert hinter der importierten Grenze mit dem Original-Seed weiter. Alte Welten (z. B.
  1.18/1.20) werden beim ersten Chunk-Besuch von Vanilla-DFU aktualisiert.
- **Inventar-Trennung pro Spieler pro Welt** (`separateInventories`, Default an): kompletter
  Spielerstatus — Inventar, Enderchest, XP, Health/Hunger, Effekte, letzte Position. Abgedeckte
  Randfälle: Portale innerhalb der Default-Gruppe (kein Swap), gruppen-übergreifende Portale
  (Swap wie `/ws`, abschaltbar), Tod ohne Bett in der Zielwelt (Post-Death-Status bleibt in der
  Todeswelt), Login-Reconciliation nach Welt-Löschung/-Entladung während man offline drin war.
- **Umbenennen ohne Datenverlust**: Welt-`id` (Dimension-Key + Inventar-Gruppe) ist fix und vom
  umbenennbaren Anzeigenamen getrennt — Inventare, Betten und Ordner überleben jede Umbenennung.
- **Server-side-only**: keine Items/Blöcke/Netzwerk-Payloads — Vanilla-1.21.1-Clients können
  ohne Mod joinen (Custom-Dimension-Key mit Vanilla-Overworld-Dimension-Type). End-to-end mit
  echtem Vanilla-Protokoll-Client (mineflayer) verifiziert: 13/13 Checks.
- **Persistenz**: Welten-Registry (`worldswitcher_worlds.dat`) + Spielerstatus
  (`worldswitcher_playerstate.dat`) als SavedData; registrierte Welten werden beim Server-Start
  automatisch neu geladen (`autoLoadOnStartup`).
