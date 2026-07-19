package net.geraldhofbauer.worldswitcher.hooks;

import javax.annotation.Nullable;

/**
 * A single configured command hook: the command line to run (without a leading slash) and an
 * optional run-as override. When {@code as} is {@code null} the service falls back to the global
 * {@code hookDefaultRunAs} config option.
 *
 * <p>In JSON a hook is either a bare string ({@code "say hi {{playerName}}"}) or an object
 * ({@code {"command": "gamemode creative {{playerName}}", "as": "server"}}).</p>
 */
public record CommandHook(String command, @Nullable HookRunAs as) {

    public CommandHook(String command) {
        this(command, null);
    }
}
