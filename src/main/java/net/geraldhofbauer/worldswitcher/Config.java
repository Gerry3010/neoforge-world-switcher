package net.geraldhofbauer.worldswitcher;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue SEPARATE_INVENTORIES = BUILDER
            .comment("Keep a separate player state (inventory, ender chest, XP, health, hunger,",
                    "effects, last position) per world group. The vanilla dimensions",
                    "(overworld/nether/end) count as one group 'default'.")
            .define("separateInventories", true);

    private static final ModConfigSpec.IntValue WS_PERMISSION_LEVEL = BUILDER
            .comment("Permission level required to use /ws (0 = all players).",
                    "/wsc always requires permission level 2 (OP).")
            .defineInRange("wsPermissionLevel", 0, 0, 4);

    private static final ModConfigSpec.ConfigValue<String> WORLDS_FOLDER = BUILDER
            .comment("Folder containing importable world saves, relative to the server root",
                    "(absolute paths allowed). /wsc import scans this folder including subpaths.")
            .define("worldsFolder", "worlds");

    private static final ModConfigSpec.BooleanValue AUTO_LOAD_ON_STARTUP = BUILDER
            .comment("Recreate all registered (not explicitly unloaded) worlds at server startup.")
            .define("autoLoadOnStartup", true);

    private static final ModConfigSpec.BooleanValue RESTORE_LAST_POSITION = BUILDER
            .comment("/ws returns players to their last position in the target world",
                    "(otherwise always the world spawn).")
            .define("restoreLastPosition", true);

    private static final ModConfigSpec.BooleanValue SWAP_GAMEMODE = BUILDER
            .comment("Include the game mode in the per-world player state.")
            .define("swapGamemode", false);

    private static final ModConfigSpec.BooleanValue HANDLE_PORTAL_GROUP_CHANGES = BUILDER
            .comment("If a portal or another mod teleports a player across world groups",
                    "(e.g. a nether portal inside an imported world), swap the player state",
                    "like /ws would. If false, such teleports are cancelled.")
            .define("handlePortalGroupChanges", true);

    private static final ModConfigSpec.BooleanValue IMPORT_COPY_ASYNC = BUILDER
            .comment("Copy imported world folders on a background thread (the world is",
                    "registered on the server thread once the copy finishes).")
            .define("importCopyAsync", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }

    public static boolean separateInventories() {
        return SEPARATE_INVENTORIES.get();
    }

    public static int wsPermissionLevel() {
        return WS_PERMISSION_LEVEL.get();
    }

    public static String worldsFolder() {
        return WORLDS_FOLDER.get();
    }

    public static boolean autoLoadOnStartup() {
        return AUTO_LOAD_ON_STARTUP.get();
    }

    public static boolean restoreLastPosition() {
        return RESTORE_LAST_POSITION.get();
    }

    public static boolean swapGamemode() {
        return SWAP_GAMEMODE.get();
    }

    public static boolean handlePortalGroupChanges() {
        return HANDLE_PORTAL_GROUP_CHANGES.get();
    }

    public static boolean importCopyAsync() {
        return IMPORT_COPY_ASYNC.get();
    }
}
