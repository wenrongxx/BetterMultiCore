package com.bettermulticore.optimize;

import com.bettermulticore.config.PluginConfig;
import com.bettermulticore.monitor.TickMonitor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Path-neighborhood chunk warmup. NEVER pauses.
 * Under high load only the per-cycle mob budget shrinks; workers keep running.
 * Does not touch entity AI.
 */
public final class PathChunkWarmer {

    private final Plugin plugin;
    private final Logger log;
    private volatile PluginConfig config;
    private volatile TickMonitor monitor;

    private ExecutorService workers;
    private Semaphore permits;
    private BukkitTask task;
    private BukkitTask drainTask;

    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final java.util.Deque<Runnable> mainKicks = new java.util.ArrayDeque<>();
    private final AtomicLong mobsScanned = new AtomicLong();
    private final AtomicLong chunksQueued = new AtomicLong();
    private final AtomicLong chunksDone = new AtomicLong();
    private final AtomicLong cycles = new AtomicLong();
    private final AtomicLong highLoadCycles = new AtomicLong();
    private final AtomicLong lastCycleNanos = new AtomicLong();
    private final AtomicInteger lastMobsThisCycle = new AtomicInteger();
    private final AtomicInteger lastQueuedThisCycle = new AtomicInteger();
    private final AtomicLong idleSkips = new AtomicLong();
    private volatile boolean idleStretchActive;

    public PathChunkWarmer(Plugin plugin, Logger log, PluginConfig config) {
        this.plugin = plugin;
        this.log = log;
        this.config = config;
    }

    public void setMonitor(TickMonitor monitor) {
        this.monitor = monitor;
    }

    public void updateConfig(PluginConfig config) {
        this.config = config;
    }

    private static final class MobJob {
        final World world;
        final int cx;
        final int cz;

        MobJob(World world, int cx, int cz) {
            this.world = world;
            this.cx = cx;
            this.cz = cz;
        }
    }

    public void start() {
        stop();
        if (!config.enabled || !config.multicoreEnabled || !config.pathwarmEnabled) {
            return;
        }
        ensurePool();
        int interval = config.pathwarmIntervalTicks;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::cycle, interval, interval);
        drainTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::drainMain, 1L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (drainTask != null) {
            drainTask.cancel();
            drainTask = null;
        }
        if (workers != null) {
            workers.shutdownNow();
            try {
                workers.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            workers = null;
            permits = null;
        }
        inFlight.clear();
        synchronized (mainKicks) {
            mainKicks.clear();
        }
    }

    private void ensurePool() {
        if (workers != null) {
            return;
        }
        PluginConfig cfg = this.config;
        int threads = Math.max(1, cfg.effectiveWorkerThreads() / 2);
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "BMC-PathWarm-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        workers = Executors.newFixedThreadPool(threads, factory);
        permits = new Semaphore(Math.max(8, cfg.effectiveMaxConcurrentLoads() / 2), true);
    }

    private boolean isHighLoad() {
        TickMonitor m = this.monitor;
        PluginConfig cfg = this.config;
        return cfg.adaptiveEnabled && m != null && m.isHighLoad();
    }

    private void cycle() {
        long t0 = System.nanoTime();
        PluginConfig cfg = this.config;
        boolean highLoad = isHighLoad();
        if (highLoad) {
            highLoadCycles.incrementAndGet();
        }
        if (workers == null) {
            ensurePool();
        }

        int prevQueued = lastQueuedThisCycle.getAndSet(0);
        lastMobsThisCycle.set(0);

        List<Player> players = Players.all(plugin);
        if (players.isEmpty()) {
            lastCycleNanos.set(System.nanoTime() - t0);
            cycles.incrementAndGet();
            return;
        }

        // High-load: stretch scan cadence (never to zero); drain still runs.
        if (highLoad) {
            int mult = Math.max(1, cfg.highLoadPathwarmIntervalMult);
            if (mult > 1 && (cycles.get() % mult) != 0) {
                drainMain();
                lastCycleNanos.set(System.nanoTime() - t0);
                cycles.incrementAndGet();
                return;
            }
        }

        // Idle economy: low MSPT + last full scan queued nothing → skip most scans.
        int stretch = Math.max(1, cfg.idleStretchMultiplier);
        TickMonitor mon = this.monitor;
        if (!highLoad && stretch > 1 && cycles.get() > 0
                && mon != null && mon.isIdle(cfg.idleMspt)
                && prevQueued == 0
                && (cycles.get() % stretch) != 0) {
            idleStretchActive = true;
            idleSkips.incrementAndGet();
            drainMain();
            lastCycleNanos.set(System.nanoTime() - t0);
            cycles.incrementAndGet();
            return;
        }
        idleStretchActive = false;

        int budget = cfg.pathwarmMaxMobsPerCycle;
        if (highLoad) {
            budget = Math.max(1, Math.min(budget, cfg.highLoadPathwarmMobs));
        }

        double scanDist = cfg.pathwarmScanDistance;
        double playerDistSq = cfg.pathwarmPlayerDistance * cfg.pathwarmPlayerDistance;
        int processed = 0;
        List<MobJob> jobs = new ArrayList<>();
        long queuedBefore = chunksQueued.get();

        for (Player player : players) {
            if (processed >= budget) {
                break;
            }
            Location pl = player.getLocation();
            java.util.Collection<Entity> nearby;
            try {
                nearby = player.getNearbyEntities(scanDist, scanDist, scanDist);
            } catch (Throwable t) {
                nearby = List.of();
            }
            if (nearby == null || nearby.isEmpty()) {
                continue;
            }
            for (Entity e : nearby) {
                if (processed >= budget) {
                    break;
                }
                if (!(e instanceof Mob)) {
                    continue;
                }
                if (e.getType() == EntityType.PLAYER || cfg.pathwarmSkipTypes.contains(e.getType().name())) {
                    continue;
                }
                if (!e.isValid() || e.isDead()) {
                    continue;
                }
                Location el = e.getLocation();
                double dx = el.getX() - pl.getX();
                double dy = el.getY() - pl.getY();
                double dz = el.getZ() - pl.getZ();
                if (dx * dx + dy * dy + dz * dz > playerDistSq) {
                    continue;
                }
                jobs.add(new MobJob(e.getWorld(), el.getBlockX() >> 4, el.getBlockZ() >> 4));
                processed++;
            }
        }

        lastMobsThisCycle.set(processed);
        mobsScanned.addAndGet(processed);

        int radius = cfg.pathwarmChunkRadius;
        if (radius >= 0 && workers != null) {
            for (MobJob job : jobs) {
                workers.execute(() -> enqueueNeighborhood(job.world, job.cx, job.cz, radius));
            }
        }

        lastQueuedThisCycle.set((int) Math.min(Integer.MAX_VALUE, chunksQueued.get() - queuedBefore));
        drainMain();
        long cost = System.nanoTime() - t0;
        lastCycleNanos.set(cost);
        TickMonitor m = this.monitor;
        if (m != null) {
            m.recordPluginMainThreadWork(cost, processed);
        }
        cycles.incrementAndGet();
    }

    private void enqueueNeighborhood(World world, int pcx, int pcz, int radius) {
        try {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int cx = pcx + dx;
                    int cz = pcz + dz;
                    if (world.isChunkLoaded(cx, cz)) {
                        continue;
                    }
                    if (!world.isChunkGenerated(cx, cz)) {
                        continue;
                    }
                    String key = world.getUID() + "|" + cx + "|" + cz;
                    if (!inFlight.add(key)) {
                        continue;
                    }
                    chunksQueued.incrementAndGet();
                    synchronized (mainKicks) {
                        mainKicks.addLast(() -> kick(world, key, cx, cz));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void drainMain() {
        PluginConfig cfg = this.config;
        int budget = cfg.normalMainDrain;
        if (cfg.adaptiveEnabled && isHighLoad()) {
            budget = cfg.highLoadMainDrain;
        }
        int n = 0;
        while (n < budget) {
            Runnable r;
            synchronized (mainKicks) {
                r = mainKicks.pollFirst();
            }
            if (r == null) {
                break;
            }
            try {
                r.run();
            } catch (Throwable ignored) {
            }
            n++;
        }
    }

    private void kick(World world, String key, int cx, int cz) {
        Semaphore p = this.permits;
        if (p == null) {
            inFlight.remove(key);
            return;
        }
        boolean acquired = false;
        try {
            acquired = p.tryAcquire(10, TimeUnit.MILLISECONDS);
            if (!acquired) {
                inFlight.remove(key);
                return;
            }
            world.getChunkAtAsync(cx, cz, false, chunk -> {
                try {
                    if (chunk != null) {
                        chunksDone.incrementAndGet();
                    }
                } finally {
                    inFlight.remove(key);
                    p.release();
                }
            });
        } catch (Throwable t) {
            inFlight.remove(key);
            if (acquired) {
                p.release();
            }
        }
    }

    public String statusLine() {
        boolean hl = isHighLoad();
        PluginConfig cfg = this.config;
        return String.format(Locale.ROOT,
                "cycles=%d highLoadCycles=%d mobs=%d lastMobs=%d queued=%d done=%d inflight=%d lastCycle=%.3fms scanR=%.0f idleSkips=%d%s%s",
                cycles.get(), highLoadCycles.get(), mobsScanned.get(), lastMobsThisCycle.get(),
                chunksQueued.get(), chunksDone.get(), inFlight.size(),
                lastCycleNanos.get() / 1_000_000.0,
                cfg.pathwarmScanDistance,
                idleSkips.get(),
                hl ? " highLoadStretch=" + Math.max(1, cfg.highLoadPathwarmIntervalMult) : "",
                idleStretchActive ? " idleStretch=ON" : "");
    }
}
