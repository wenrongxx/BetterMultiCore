package com.bettersinglecore.optimize;

import com.bettersinglecore.config.PluginConfig;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Puts distant mobs into AI dormancy so the main thread stops paying for
 * pathfinding / random stroll / target selection on entities nobody can see.
 *
 * Design notes:
 * - Scans are time-budgeted (max-scan-ms) so a huge entity count cannot stall a tick.
 * - Only Mob instances are considered; skip-types are cheap string lookups on EntityType name.
 * - wakeDistance = dormantDistance - wakeMargin to avoid thrashing at the boundary.
 */
public final class AiDormancyService {

    private final Plugin plugin;
    private final Logger log;
    private volatile PluginConfig config;

    private BukkitTask task;
    /** Global resume cursor across worlds: flat index into concatenated entity lists. */
    private long flatCursor;

    private long dormantNow;
    private long awakenedNow;
    private long totalDormantApplied;
    private long totalAwakened;
    private long scans;
    private long lastScanNanos;
    private long lastScanProcessed;

    /** entity UUID -> currently dormant (managed by this plugin) */
    private final Map<UUID, Boolean> managed = new HashMap<>();

    public AiDormancyService(Plugin plugin, Logger log, PluginConfig config) {
        this.plugin = plugin;
        this.log = log;
        this.config = config;
    }

    public void updateConfig(PluginConfig config) {
        this.config = config;
    }

    public void start() {
        stop();
        if (!config.enabled || !config.dormancyEnabled) {
            return;
        }
        int interval = effectiveScanInterval();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::scanOnce, interval, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void wakeAll() {
        for (UUID id : managed.keySet()) {
            Entity e = plugin.getServer().getEntity(id);
            if (e instanceof Mob mob && config.disableAiFlag) {
                mob.setAI(true);
            }
            if (e instanceof Mob mob) {
                mob.setAware(true);
            }
        }
        long n = managed.size();
        managed.clear();
        totalAwakened += n;
        if (n > 0) {
            log.info("[BSC] Woke " + n + " dormant mobs (disable/reload).");
        }
    }

    private int effectiveScanInterval() {
        int base = config.dormancyScanIntervalTicks;
        if (config.budgetEnabled && isAggressive()) {
            return base * config.aggressiveDormancyIntervalMultiplier;
        }
        return base;
    }

    private boolean isAggressive() {
        // TickMonitor feeds this via isOverloaded; service asks through config path each start.
        // Aggressive re-evaluation is done by plugin on monitor state change; here we just use last start value.
        return aggressiveFlag;
    }

    private volatile boolean aggressiveFlag;

    public void setAggressive(boolean aggressive) {
        if (aggressive == aggressiveFlag) {
            return;
        }
        aggressiveFlag = aggressive;
        // Restart with new interval if running
        if (task != null) {
            start();
        }
        log.info("[BSC] Dormancy mode -> " + (aggressive ? "AGGRESSIVE" : "NORMAL"));
    }

    private void scanOnce() {
        long begin = System.nanoTime();
        PluginConfig cfg = this.config;
        dormantNow = 0;
        awakenedNow = 0;
        lastScanProcessed = 0;

        List<org.bukkit.World> worlds = plugin.getServer().getWorlds();
        if (worlds.isEmpty()) {
            return;
        }

        Map<org.bukkit.World, List<Player>> playersByWorld = new HashMap<>();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            playersByWorld.computeIfAbsent(p.getWorld(), w -> new ArrayList<>()).add(p);
        }

        // Flatten entity snapshots per world into a working list of steps.
        // Budget applies to processed mobs/entities, not list construction.
        List<org.bukkit.World> worldSlice = new ArrayList<>(worlds.size());
        List<List<Entity>> entitySlices = new ArrayList<>(worlds.size());
        long totalEntities = 0;
        for (org.bukkit.World w : worlds) {
            List<Entity> list = w.getEntities();
            worldSlice.add(w);
            entitySlices.add(list);
            totalEntities += list.size();
        }
        if (totalEntities == 0) {
            flatCursor = 0;
            return;
        }
        if (flatCursor >= totalEntities) {
            flatCursor = 0;
        }

        int budget = cfg.maxEntitiesPerScan;
        double budgetNanos = cfg.budgetEnabled ? cfg.maxScanMs * 1_000_000.0 : Double.MAX_VALUE;
        int processed = 0;
        long index = flatCursor;
        long visited = 0;

        while (processed < budget && visited < totalEntities) {
            // Resolve flat index -> (world, entity)
            org.bukkit.World world = null;
            Entity entity = null;
            List<Player> players = List.of();
            long remaining = index;
            for (int wi = 0; wi < entitySlices.size(); wi++) {
                List<Entity> slice = entitySlices.get(wi);
                if (remaining < slice.size()) {
                    world = worldSlice.get(wi);
                    entity = slice.get((int) remaining);
                    players = playersByWorld.getOrDefault(world, List.of());
                    break;
                }
                remaining -= slice.size();
            }
            if (entity == null) {
                flatCursor = 0;
                break;
            }

            stepEntity(entity, players, cfg);
            processed++;
            visited++;
            index++;
            if (index >= totalEntities) {
                index = 0;
            }

            if ((processed & 31) == 0 && (System.nanoTime() - begin) > budgetNanos) {
                break;
            }
        }

        flatCursor = index;
        lastScanProcessed = processed;
        lastScanNanos = System.nanoTime() - begin;
        scans++;
        totalDormantApplied += dormantNow;
        totalAwakened += awakenedNow;
    }

    private void stepEntity(Entity entity, List<Player> players, PluginConfig cfg) {
        if (!(entity instanceof Mob mob)) {
            return;
        }
        if (!mob.isValid() || mob.isDead()) {
            managed.remove(entity.getUniqueId());
            return;
        }

        EntityType type = entity.getType();
        String typeName = type.name();
        if (cfg.skipTypes.contains(typeName)) {
            return;
        }

        if (cfg.neverDormantIfPassenger && entity.getVehicle() != null) {
            return;
        }
        if (cfg.neverDormantIfDriving && !entity.getPassengers().isEmpty()) {
            return;
        }

        UUID id = entity.getUniqueId();
        boolean wasDormant = managed.getOrDefault(id, false);

        if (players.isEmpty()) {
            // No players in this world: only keep entities already awake if they were never managed,
            // otherwise put them to sleep (world unload edge — still safe).
            if (!wasDormant && shouldConsiderDormant(entity, cfg, Double.MAX_VALUE)) {
                applyDormant(mob, id, cfg);
            }
            return;
        }

        double nearest = nearestPlayerDistanceSq(entity.getLocation(), players);
        double dormantDistSq = cfg.dormantDistance * cfg.dormantDistance;
        double wakeDistSq = cfg.wakeDistance() * cfg.wakeDistance();

        if (!wasDormant) {
            if (nearest > dormantDistSq) {
                applyDormant(mob, id, cfg);
            }
        } else {
            // Hysteresis: wake only when inside wakeDistance.
            if (nearest <= wakeDistSq) {
                applyAwake(mob, id, cfg);
            }
        }
    }

    private boolean shouldConsiderDormant(Entity entity, PluginConfig cfg, double nearestDistSq) {
        return nearestDistSq > cfg.dormantDistance * cfg.dormantDistance;
    }

    private static double nearestPlayerDistanceSq(Location loc, List<Player> players) {
        double best = Double.MAX_VALUE;
        double x = loc.getX();
        double y = loc.getY();
        double z = loc.getZ();
        for (Player p : players) {
            if (p.getWorld() != loc.getWorld()) {
                continue;
            }
            Location pl = p.getLocation();
            double dx = pl.getX() - x;
            double dy = pl.getY() - y;
            double dz = pl.getZ() - z;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < best) {
                best = d;
            }
        }
        return best;
    }

    private void applyDormant(Mob mob, UUID id, PluginConfig cfg) {
        mob.setAware(false);
        if (cfg.disableAiFlag) {
            mob.setAI(false);
        }
        managed.put(id, true);
        dormantNow++;
    }

    private void applyAwake(Mob mob, UUID id, PluginConfig cfg) {
        mob.setAware(true);
        if (cfg.disableAiFlag) {
            mob.setAI(true);
        }
        managed.put(id, false);
        awakenedNow++;
    }

    public String statusLine() {
        return String.format(Locale.ROOT,
                "dormant=%d scans=%d lastProcessed=%d lastScan=%.3fms applied=%d woke=%d",
                managed.values().stream().filter(Boolean::booleanValue).count(),
                scans, lastScanProcessed, lastScanNanos / 1_000_000.0,
                totalDormantApplied, totalAwakened);
    }

    public int getManagedCount() {
        return managed.size();
    }

    public long getTotalDormantApplied() {
        return totalDormantApplied;
    }

    public long getTotalAwakened() {
        return totalAwakened;
    }

    public long getScans() {
        return scans;
    }

    public double getLastScanMs() {
        return lastScanNanos / 1_000_000.0;
    }
}
