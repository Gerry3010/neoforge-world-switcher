# Changelog
All notable changes to World Switcher will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]
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
