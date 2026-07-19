package net.geraldhofbauer.worldswitcher.hooks;

import javax.annotation.Nullable;

/**
 * The three world-lifecycle triggers a command hook can react to.
 *
 * <p>World identity follows the World Switcher grouping ({@link
 * net.geraldhofbauer.worldswitcher.world.WorldRegistry#groupOf}): the vanilla
 * overworld/nether/end count as one world {@code default}, every managed world is its own.</p>
 */
public enum HookEvent {

    /** A world's occupant count goes 0 → 1 (the first player enters an empty world). */
    FIRST_PLAYER_JOIN("firstPlayerJoin"),

    /** A world's occupant count goes 1 → 0 (the last player leaves the world). */
    LAST_PLAYER_LEAVE("lastPlayerLeave"),

    /** A player changes world (source world ≠ destination world). */
    PLAYER_MOVED("playerMoved");

    private final String jsonKey;

    HookEvent(String jsonKey) {
        this.jsonKey = jsonKey;
    }

    /** The key used for this event in {@code worldswitcher-hooks.json}. */
    public String jsonKey() {
        return jsonKey;
    }

    /** Resolves an event from its JSON key, or {@code null} if unknown. */
    @Nullable
    public static HookEvent byJsonKey(String key) {
        for (HookEvent event : values()) {
            if (event.jsonKey.equals(key)) {
                return event;
            }
        }
        return null;
    }
}
