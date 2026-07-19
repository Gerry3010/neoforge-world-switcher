## World Switcher 1.4.0

Second Modrinth release. Since the initial public `1.0.0`, this version bundles
everything from `1.1.0`, `1.2.0`, `1.3.0` and `1.4.0` — the headline feature is the
new **command-hook system**.

### 1.4.0 – Command Hooks
- **Command hooks** (`enableCommandHooks`, on by default): define per-world and global
  command lists in `serverconfig/worldswitcher-hooks.json` that run automatically on:
  - `firstPlayerJoin` – a world goes from 0 → 1 players
  - `lastPlayerLeave` – a world goes from 1 → 0 players
  - `playerMoved` – a player switches world
  Variables `{{worldName}}`, `{{worldId}}`, `{{playerName}}`, `{{playerUuid}}` are
  substituted in every command. Each hook runs `as: server` (OP 4, output suppressed,
  positioned at the world) or `as: player`; without an `as`, `hookDefaultRunAs`
  (default `server`) applies. Global hooks run before per-world hooks. Failing commands
  are logged and never interrupt the switch/login/logout. Live-reload with
  `/wsc hooks reload` (inspect via `/wsc hooks status`). Great for pausing
  `doDaylightCycle`/`doWeatherCycle` (and Serene Seasons' `doSeasonCycle`) while a world
  is empty – see the README for a ready-made example.
- **Clickable switch announcement** (`announceSwitches`, on by default): when a player
  switches world, everyone else sees a chat message whose world name is a clickable
  `/ws <world>` link to follow along.
- **Per-world shared inventory** (`/wsc shareinventory <world> [true|false]`): mark a
  world as "keep-inventory" so it uses the `default` inventory group instead of its own.
- Changed: `persistentDataExcludes` now defaults to empty.

### 1.2.0 – Modded player state & per-world difficulty
- **Modded player state per world** (part of `separateInventories`), with no mod
  dependencies:
  - NeoForge data attachments (`swapModAttachments`) – e.g. Curios slots swap per world.
  - Persistent data (`swapPersistentData`) – player NBT under `NeoForgeData`.
  - Tough As Nails (`swapToughAsNails`) – thirst + temperature per world (via reflection).
- **Per-world difficulty** (`perWorldDifficulty`): `/difficulty` only affects the managed
  world; new `/wsc difficulty <world> [value]`. Imports adopt difficulty from `level.dat`.
- **Safe relog after a world was unloaded**: players offline in a removed/unloaded world
  are teleported to their last default position/spawn instead of unsafe old coordinates.
- Fixed: modded player data (e.g. Curios) previously leaked between worlds.

### 1.1.0 – Per-world gamerules, time & weather
- **Per-world gamerules** (`perWorldGameRules`): each managed world has its own gamerules
  (also works with modded rules like Serene Seasons' `doSeasonCycle`).
- **Per-world time & weather** (`perWorldTimeAndWeather`): own clock and weather per world
  (eternal night, permanent rain, frozen time). Sleeping only skips your own world's night.
- **Context-sensitive vanilla commands**: `/gamerule`, `/time`, `/weather` affect only the
  managed world you're in; `/execute in worldswitcher:<id> run …` targets a world from
  anywhere; new `/wsc gamerule <world> [<rule> [value]]`.
- Client-side rules (`doImmediateRespawn`, `reducedDebugInfo`, `doLimitedCrafting`) are
  re-synced to the client on every switch/respawn.
- Bare `/ws` shows the (clickable) world list with a "you are here" marker; bare `/wsc`
  shows an action overview.
- Changed: `swapGamemode` is now on by default.
- Fixed: `/wsc list` now includes the `default` world; correct item handling on
  cross-world death with differing `keepInventory`.
