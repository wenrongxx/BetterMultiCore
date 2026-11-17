package com.bettersinglecore.optimize;

import com.bettersinglecore.config.PluginConfig;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Asynchronously warms chunks around players so the main thread does not pay
 * for first-touch chunk load/generation spikes during exploration.
 *
 * Kick cost on the main thread is time-budgeted; actual IO runs off-thread via
 * Paper's getChunkAtAsync.
 */
public final class ChunkPreloaderService {

    private final Plugin plugin;
    private final Logger log;
    private volatile PluginConfig config;

    private BukkitTask task;
    private boolean aggressive;

    private long cycles;
    private long loadsStarted;
    private long loadsCompleted;
    private long loadsFailed;
    private long lastKickNanos;
    private int lastLoadsThisCycle;

    /** In-flight keys to avoid duplicate kicks: worldUid|cx|cz */
    private final Set<String> inFlight = new HashSet<>();
    private final Deque<String> recentCompleted = new ArrayDeque<>();

    public ChunkPreloaderService(Plugin plugin, Logger log, PluginConfig config) {
        this.plugin = plugin;
        this.log = log;
        this.config = config;
    }

    public void updateConfig(PluginConfig config) {
        this.config = config;
    }

    public void setAggressive(boolean aggressive) {
        this.aggressive = aggressive;
    }

    public void start() {
        stop();
        if (!config.enabled || !config.preloadEnabled) {
            return;
        }
        int interval = config.preloadIntervalTicks;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::cycle, interval, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        inFlight.clear();
    }

    private int effectiveRadius() {
        int r = config.preloadRadiusChunks;
        if (aggressive && config.budgetEnabled) {
            r = Math.max(config.aggressivePreloadRadiusMin, r - 1);
        }
        return Math.max(0, r);
    }

    private void cycle() {
        long begin = System.nanoTime();
        PluginConfig cfg = this.config;
        lastLoadsThisCycle = 0;

        int radius = effectiveRadius();
        int maxLoads = cfg.maxLoadsPerCycle;
        double budgetNanos = cfg.budgetEnabled ? cfg.maxPreloadKickMs * 1_000_000.0 : Double.MAX_VALUE;

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (lastLoadsThisCycle >= maxLoads) {
                break;
            }
            if ((System.nanoTime() - begin) > budgetNanos) {
                break;
            }

            World world = player.getWorld();
            Location loc = player.getLocation();
            int pcx = loc.getBlockX() >> 4;
            int pcz = loc.getBlockZ() >> 4;
            String worldKey = world.getUID().toString();

            // Spiral-ish scan from center so nearest chunks win under budget.
            for (int dx = -radius; dx <= radius && lastLoadsThisCycle < maxLoads; dx++) {
                for (int dz = -radius; dz <= radius && lastLoadsThisCycle < maxLoads; dz++) {
                    if ((System.nanoTime() - begin) > budgetNanos) {
                        lastKickNanos = System.nanoTime() - begin;
                        cycles++;
                        return;
                    }
                    int cx = pcx + dx;
                    int cz = pcz + dz;
                    String key = worldKey + "|" + cx + "|" + cz;
                    if (inFlight.contains(key)) {
                        continue;
                    }
                    if (world.isChunkLoaded(cx, cz)) {
                        continue;
                    }
                    if (!cfg.generateIfMissing && !world.isChunkGenerated(cx, cz)) {
                        continue;
                    }

                    inFlight.add(key);
                    lastLoadsThisCycle++;
                    loadsStarted++;

                    world.getChunkAtAsync(cx, cz, cfg.generateIfMissing, chunk -> onLoaded(key, chunk));
                }
            }
        }

        lastKickNanos = System.nanoTime() - begin;
        cycles++;
    }

    private void onLoaded(String key, Chunk chunk) {
        // Callback may run off-main; only touch counters (atomic-ish via synchronized block).
        synchronized (this) {
            inFlight.remove(key);
            if (chunk != null) {
                loadsCompleted++;
                recentCompleted.addLast(key);
                while (recentCompleted.size() > 64) {
                    recentCompleted.removeFirst();
                }
            } else {
                loadsFailed++;
            }
        }
    }

    public String statusLine() {
        synchronized (this) {
            return String.format(Locale.ROOT,
                    "cycles=%d started=%d done=%d fail=%d inflight=%d lastKick=%.3fms lastCycleLoads=%d radius=%d",
                    cycles, loadsStarted, loadsCompleted, loadsFailed,
                    inFlight.size(), lastKickNanos / 1_000_000.0, lastLoadsThisCycle, effectiveRadius());
        }
    }

    public long getLoadsStarted() {
        return loadsStarted;
    }

    public long getLoadsCompleted() {
        return loadsCompleted;
    }
}
