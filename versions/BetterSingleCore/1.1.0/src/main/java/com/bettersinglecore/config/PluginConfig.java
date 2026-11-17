package com.bettersinglecore.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class PluginConfig {

    public final boolean enabled;

    public final boolean multicoreEnabled;
    public final int workerThreads;
    public final int maxConcurrentLoads;
    public final String threadNamePrefix;

    public final boolean preloadEnabled;
    public final int preloadIntervalTicks;
    public final int preloadRadiusChunks;
    public final int maxLoadsPerPlayer;
    public final boolean generateIfMissing;
    public final boolean useBatchRect;

    public final boolean pathwarmEnabled;
    public final int pathwarmIntervalTicks;
    public final double pathwarmPlayerDistance;
    public final int pathwarmChunkRadius;
    public final int pathwarmMaxMobsPerCycle;
    public final Set<String> pathwarmSkipTypes;

    public final boolean monitorEnabled;
    public final int reportIntervalTicks;
    public final double overloadedMspt;
    public final int historySize;

    public final boolean logSummaryOnEnable;

    private PluginConfig(FileConfiguration c) {
        this.enabled = c.getBoolean("enabled", true);

        this.multicoreEnabled = c.getBoolean("multicore.enabled", true);
        this.workerThreads = Math.max(0, c.getInt("multicore.worker-threads", 0));
        this.maxConcurrentLoads = Math.max(0, c.getInt("multicore.max-concurrent-loads", 0));
        this.threadNamePrefix = c.getString("multicore.thread-name-prefix", "BSC-Worker");

        this.preloadEnabled = c.getBoolean("preload.enabled", true);
        this.preloadIntervalTicks = Math.max(5, c.getInt("preload.interval-ticks", 20));
        this.preloadRadiusChunks = Math.max(0, c.getInt("preload.radius-chunks", 2));
        this.maxLoadsPerPlayer = Math.max(1, c.getInt("preload.max-loads-per-player", 36));
        this.generateIfMissing = c.getBoolean("preload.generate-if-missing", false);
        this.useBatchRect = c.getBoolean("preload.use-batch-rect", true);

        this.pathwarmEnabled = c.getBoolean("pathwarm.enabled", true);
        this.pathwarmIntervalTicks = Math.max(5, c.getInt("pathwarm.interval-ticks", 40));
        this.pathwarmPlayerDistance = Math.max(8.0, c.getDouble("pathwarm.player-distance", 64.0));
        this.pathwarmChunkRadius = Math.max(0, c.getInt("pathwarm.chunk-radius", 1));
        this.pathwarmMaxMobsPerCycle = Math.max(0, c.getInt("pathwarm.max-mobs-per-cycle", 80));
        this.pathwarmSkipTypes = new HashSet<>();
        List<String> skips = c.getStringList("pathwarm.skip-types");
        if (skips != null) {
            for (String s : skips) {
                if (s != null && !s.isBlank()) {
                    this.pathwarmSkipTypes.add(s.trim().toUpperCase(Locale.ROOT));
                }
            }
        }

        this.monitorEnabled = c.getBoolean("monitor.enabled", true);
        this.reportIntervalTicks = Math.max(0, c.getInt("monitor.report-interval-ticks", 1200));
        this.overloadedMspt = Math.max(1.0, c.getDouble("monitor.overloaded-mspt", 50.0));
        this.historySize = Math.max(10, c.getInt("monitor.history-size", 120));

        this.logSummaryOnEnable = c.getBoolean("misc.log-summary-on-enable", true);
    }

    public static PluginConfig from(FileConfiguration c) {
        return new PluginConfig(c);
    }

    public int effectiveWorkerThreads() {
        if (workerThreads > 0) {
            return workerThreads;
        }
        return Math.max(2, Runtime.getRuntime().availableProcessors());
    }

    public int effectiveMaxConcurrentLoads() {
        if (maxConcurrentLoads > 0) {
            return maxConcurrentLoads;
        }
        return effectiveWorkerThreads() * 8;
    }

    public String summary() {
        return "enabled=" + enabled
                + ", workers=" + effectiveWorkerThreads()
                + ", maxLoads=" + effectiveMaxConcurrentLoads()
                + ", preload=" + preloadEnabled + "@" + preloadIntervalTicks + "t/r" + preloadRadiusChunks
                + ", pathwarm=" + pathwarmEnabled + "@" + pathwarmIntervalTicks + "t"
                + ", monitor=" + monitorEnabled;
    }
}
