package com.bettersinglecore.optimize;

import com.bettersinglecore.config.PluginConfig;
import com.bettersinglecore.monitor.TickMonitor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
 * Multi-core chunk warmup that NEVER pauses.
 *
 * Under high MSPT, only the main-thread drain budget shrinks so each tick
 * spends less time on kicks; planning stays on the worker pool and queued
 * kicks continue across subsequent ticks (work is spread, not stopped).
 */
public final class ParallelChunkEngine {

    private final Plugin plugin;
    private final Logger log;
    private volatile PluginConfig config;
    private volatile TickMonitor monitor;

    private ExecutorService workers;
    private Semaphore loadPermits;
    private BukkitTask cycleTask;
    private BukkitTask drainTask;

    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicLong loadsStarted = new AtomicLong();
    private final AtomicLong loadsCompleted = new AtomicLong();
    private final AtomicLong loadsFailed = new AtomicLong();
    private final AtomicLong batchCalls = new AtomicLong();
    private final AtomicLong cycles = new AtomicLong();
    private final AtomicLong lastKickNanos = new AtomicLong();
    private final AtomicInteger lastLoadsThisCycle = new AtomicInteger();

    private final Deque<Runnable> mainThreadKicks = new ArrayDeque<>();

    public ParallelChunkEngine(Plugin plugin, Logger log, PluginConfig config) {
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

    private static final class Plan {
        final World world;
        final int pcx;
        final int pcz;
        final int radius;
        final int maxLoads;
        final boolean generate;
        final boolean batch;

        Plan(World world, int pcx, int pcz, int radius, int maxLoads, boolean generate, boolean batch) {
            this.world = world;
            this.pcx = pcx;
            this.pcz = pcz;
            this.radius = radius;
            this.maxLoads = maxLoads;
            this.generate = generate;
            this.batch = batch;
        }
    }

    public void start() {
        stop();
        if (!config.enabled || !config.multicoreEnabled || !config.preloadEnabled) {
            return;
        }
        ensurePool();
        int interval = config.preloadIntervalTicks;
        cycleTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::beginCycle, interval, interval);
        drainTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::drainMainKicks, 1L, 1L);
    }

    public void stop() {
        if (cycleTask != null) {
            cycleTask.cancel();
            cycleTask = null;
        }
        if (drainTask != null) {
            drainTask.cancel();
            drainTask = null;
        }
        shutdownPool();
        inFlight.clear();
        synchronized (mainThreadKicks) {
            mainThreadKicks.clear();
        }
    }

    public void ensurePool() {
        PluginConfig cfg = this.config;
        if (workers != null) {
            return;
        }
        int threads = cfg.effectiveWorkerThreads();
        int permits = cfg.effectiveMaxConcurrentLoads();
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, cfg.threadNamePrefix + "-" + n.incrementAndGet());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
        };
        workers = Executors.newFixedThreadPool(threads, factory);
        loadPermits = new Semaphore(permits, true);
        log.info(String.format(Locale.ROOT,
                "[BSC] Multi-core pool: %d workers, max concurrent chunk loads=%d (CPUs=%d)",
                threads, permits, Runtime.getRuntime().availableProcessors()));
    }

    private void shutdownPool() {
        if (workers != null) {
            workers.shutdownNow();
            try {
                workers.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            workers = null;
            loadPermits = null;
        }
    }

    private boolean isHighLoad() {
        TickMonitor m = this.monitor;
        return config.adaptiveEnabled && m != null && m.isHighLoad();
    }

    private void beginCycle() {
        long t0 = System.nanoTime();
        lastLoadsThisCycle.set(0);
        PluginConfig cfg = this.config;
        boolean highLoad = isHighLoad();

        List<Player> players = Players.all(plugin);
        if (cycles.get() == 0 || cycles.get() % 60 == 0) {
            Players.logCount(log, players);
        }
        if (players.isEmpty()) {
            lastKickNanos.set(System.nanoTime() - t0);
            cycles.incrementAndGet();
            return;
        }

        if (workers == null) {
            ensurePool();
        }
        if (workers == null) {
            return;
        }

        int radius = cfg.preloadRadiusChunks;
        boolean useIdleBonus = !highLoad || cfg.highLoadUseIdleRadius;
        if (useIdleBonus && cfg.idleRadiusBonus > 0) {
            radius += cfg.idleRadiusBonus;
        }

        List<Plan> plans = new ArrayList<>(players.size());
        for (Player p : players) {
            Location loc = p.getLocation();
            plans.add(new Plan(
                    p.getWorld(),
                    loc.getBlockX() >> 4,
                    loc.getBlockZ() >> 4,
                    radius,
                    cfg.maxLoadsPerPlayer,
                    cfg.generateIfMissing,
                    cfg.useBatchRect
            ));
        }

        // Planning always on workers — never skipped.
        for (Plan plan : plans) {
            workers.execute(() -> planAndEnqueue(plan));
        }

        drainMainKicks();
        long cost = System.nanoTime() - t0;
        lastKickNanos.set(cost);
        TickMonitor m = this.monitor;
        if (m != null) {
            m.recordPluginMainThreadWork(cost, lastLoadsThisCycle.get());
        }
        cycles.incrementAndGet();
    }

    private void planAndEnqueue(Plan plan) {
        try {
            World world = plan.world;
            int r = plan.radius;
            List<int[]> targets = new ArrayList<>();
            for (int ring = 0; ring <= r; ring++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                            continue;
                        }
                        targets.add(new int[]{plan.pcx + dx, plan.pcz + dz});
                    }
                }
            }

            if (plan.batch && r > 0) {
                String key = world.getUID() + "|rect|" + plan.pcx + "," + plan.pcz + "," + r;
                if (inFlight.add(key)) {
                    enqueueMain(() -> {
                        try {
                            int minx = plan.pcx - r;
                            int minz = plan.pcz - r;
                            int maxx = plan.pcx + r;
                            int maxz = plan.pcz + r;
                            int side = 2 * r + 1;
                            int n = side * side;
                            batchCalls.incrementAndGet();
                            loadsStarted.addAndGet(n);
                            world.getChunksAtAsync(minx, minz, maxx, maxz, plan.generate, () -> {
                                inFlight.remove(key);
                                loadsCompleted.addAndGet(n);
                            });
                        } catch (Throwable t) {
                            inFlight.remove(key);
                            loadsFailed.incrementAndGet();
                        }
                    });
                    return;
                }
            }

            int budget = plan.maxLoads;
            int kicked = 0;
            for (int[] c : targets) {
                if (kicked >= budget) {
                    break;
                }
                int cx = c[0];
                int cz = c[1];
                String key = world.getUID() + "|" + cx + "|" + cz;
                if (inFlight.contains(key)) {
                    continue;
                }
                if (world.isChunkLoaded(cx, cz)) {
                    continue;
                }
                if (!plan.generate && !world.isChunkGenerated(cx, cz)) {
                    continue;
                }
                if (!inFlight.add(key)) {
                    continue;
                }
                final int fx = cx;
                final int fz = cz;
                enqueueMain(() -> kickOne(world, key, fx, fz, plan.generate));
                kicked++;
                lastLoadsThisCycle.incrementAndGet();
            }
        } catch (Throwable ignored) {
        }
    }

    private void enqueueMain(Runnable r) {
        synchronized (mainThreadKicks) {
            mainThreadKicks.addLast(r);
        }
    }

    private void drainMainKicks() {
        PluginConfig cfg = this.config;
        int budget = cfg.normalMainDrain;
        if (cfg.adaptiveEnabled && isHighLoad()) {
            budget = cfg.highLoadMainDrain;
        }
        int drained = 0;
        while (drained < budget) {
            Runnable r;
            synchronized (mainThreadKicks) {
                r = mainThreadKicks.pollFirst();
            }
            if (r == null) {
                break;
            }
            try {
                r.run();
            } catch (Throwable t) {
                log.warning("[BSC] kick failed: " + t);
            }
            drained++;
        }
    }

    private void kickOne(World world, String key, int cx, int cz, boolean generate) {
        Semaphore permits = this.loadPermits;
        if (permits == null) {
            inFlight.remove(key);
            return;
        }
        boolean acquired = false;
        try {
            acquired = permits.tryAcquire(20, TimeUnit.MILLISECONDS);
            if (!acquired) {
                inFlight.remove(key);
                return;
            }
            loadsStarted.incrementAndGet();
            world.getChunkAtAsync(cx, cz, generate, chunk -> {
                try {
                    if (chunk != null) {
                        loadsCompleted.incrementAndGet();
                    } else {
                        loadsFailed.incrementAndGet();
                    }
                } finally {
                    inFlight.remove(key);
                    permits.release();
                }
            });
        } catch (Throwable t) {
            inFlight.remove(key);
            if (acquired) {
                permits.release();
            }
            loadsFailed.incrementAndGet();
        }
    }

    public String statusLine() {
        boolean hl = isHighLoad();
        int drain = config.adaptiveEnabled && hl
                ? config.highLoadMainDrain
                : config.normalMainDrain;
        return String.format(Locale.ROOT,
                "workers=%d maxLoads=%d highLoad=%s drain=%d cycles=%d started=%d done=%d fail=%d inflight=%d batch=%d lastKick=%.3fms",
                config.effectiveWorkerThreads(),
                config.effectiveMaxConcurrentLoads(),
                hl, drain, cycles.get(), loadsStarted.get(), loadsCompleted.get(), loadsFailed.get(),
                inFlight.size(), batchCalls.get(),
                lastKickNanos.get() / 1_000_000.0);
    }

    public long getLoadsStarted() {
        return loadsStarted.get();
    }

    public long getLoadsCompleted() {
        return loadsCompleted.get();
    }
}
