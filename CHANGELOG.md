# Changelog
All notable changes to World Switcher will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
