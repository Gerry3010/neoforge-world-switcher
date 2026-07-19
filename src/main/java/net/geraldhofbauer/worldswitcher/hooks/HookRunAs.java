package net.geraldhofbauer.worldswitcher.hooks;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * Who a hook command runs as.
 *
 * <ul>
 *   <li>{@link #SERVER} — the server command source at OP level 4, output suppressed, positioned
 *       at the world (like the console running the command).</li>
 *   <li>{@link #PLAYER} — the triggering player's own command source, bound by their permission
 *       level.</li>
 * </ul>
 */
public enum HookRunAs {
    SERVER,
    PLAYER;

    /** Parses {@code "server"}/{@code "player"} (case-insensitive), or {@code null} if unknown. */
    @Nullable
    public static HookRunAs byName(@Nullable String name) {
        if (name == null) {
            return null;
        }
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "server" -> SERVER;
            case "player" -> PLAYER;
            default -> null;
        };
    }
}
