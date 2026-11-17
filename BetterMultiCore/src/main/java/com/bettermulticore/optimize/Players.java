package com.bettermulticore.optimize;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Collects real players plus Leaves fake players (bots).
 * Bots are NOT in Server#getOnlinePlayers() / World#getPlayers() on this build;
 * they must be read via Server#getBotManager().
 */
public final class Players {

    private Players() {
    }

    public static List<Player> all(Plugin plugin) {
        Map<UUID, Player> byId = new LinkedHashMap<>();

        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p != null) {
                byId.put(p.getUniqueId(), p);
            }
        }

        // Leaves bots (fake players)
        try {
            var botManager = plugin.getServer().getBotManager();
            if (botManager != null) {
                Collection<? extends Player> bots = botManager.getBots();
                if (bots != null) {
                    for (Player p : bots) {
                        if (p != null) {
                            byId.putIfAbsent(p.getUniqueId(), p);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // Not a Leaves server / API mismatch — online players only.
        }

        // Fallback world scan only when bots API is missing and online list is empty
        // (full world.getPlayers() walks every world — expensive on large servers).
        if (byId.isEmpty()) {
            for (org.bukkit.World world : plugin.getServer().getWorlds()) {
                try {
                    for (Player p : world.getPlayers()) {
                        if (p != null) {
                            byId.putIfAbsent(p.getUniqueId(), p);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        return new ArrayList<>(byId.values());
    }

    public static void logCount(Logger log, List<Player> players) {
        if (log == null || players == null || players.size() <= 4) {
            return; // avoid log spam on small pop; count is still in status lines
        }
        log.info("[BMC] Players.all size=" + players.size());
    }
}
