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
    public final int idleRadiusBonus;

    public final boolean pathwarmEnabled;
    public final int pathwarmIntervalTicks;
    public final double pathwarmPlayerDistance;
    public final int pathwarmChunkRadius;
    public final int pathwarmMaxMobsPerCycle;
    public final Set<String> pathwarmSkipTypes;

    public final boolean adaptiveEnabled;
    public final double highLoadMspt;
    public final int highLoadAfterTicks;
    public final double normalMspt;
    public final int normalAfterTicks;
    public final int normalMainDrain;
    public final int highLoadMainDrain;
    public final int highLoadPathwarmMobs;
    public final boolean highLoadUseIdleRadius;

    public final boolean monitorEnabled;
    public final int reportIntervalTicks;
    public final double overloadedMspt;
    public final int historySize;
    public final boolean reportPluginCost;

    public final boolean logSummaryOnEnable;

    private PluginConfig(FileConfiguration c) {
        this.enabled = c.getBoolean("enabled", true);

        this.multicoreEnabled = c.getBoolean("multicore.enabled", true);
        this.workerThreads = Math.max(0, c.getInt("multicore.worker-threads", 0));
        this.maxConcurrentLoads = Math.max(0, c.getInt("multicore.max-concurrent-loads", 0));
        this.threadNamePrefix = c.getString("multicore.thread-name-prefix", "BSC-Worker");

        this.preloadEnabled = c.getBoolean("preload.enabled", true);
        this.preloadIntervalTicks = Math.max(5, c.getInt("preload.interval-ticks", 20));
        this.preloadRadiusChunks = Math.max(0, c.getInt("preload.radius-chunks", 3));
        this.maxLoadsPerPlayer = Math.max(1, c.getInt("preload.max-loads-per-player", 64));
        this.generateIfMissing = c.getBoolean("preload.generate-if-missing", false);
        this.useBatchRect = c.getBoolean("preload.use-batch-rect", true);
        this.idleRadiusBonus = Math.max(0, c.getInt("preload.idle-radius-bonus", 1));

        this.pathwarmEnabled = c.getBoolean("pathwarm.enabled", true);
        this.pathwarmIntervalTicks = Math.max(5, c.getInt("pathwarm.interval-ticks", 40));
        this.pathwarmPlayerDistance = Math.max(8.0, c.getDouble("pathwarm.player-distance", 48.0));
        this.pathwarmChunkRadius = Math.max(0, c.getInt("pathwarm.chunk-radius", 1));
        this.pathwarmMaxMobsPerCycle = Math.max(1, c.getInt("pathwarm.max-mobs-per-cycle", 48));
        this.pathwarmSkipTypes = new HashSet<>();
        List<String> skips = c.getStringList("pathwarm.skip-types");
        if (skips != null) {
            for (String s : skips) {
                if (s != null && !s.isBlank()) {
                    this.pathwarmSkipTypes.add(s.trim().toUpperCase(Locale.ROOT));
                }
            }
        }

        this.adaptiveEnabled = c.getBoolean("adaptive.enabled", true);
        this.highLoadMspt = Math.max(5.0, c.getDouble("adaptive.high-load-mspt", 25.0));
        this.highLoadAfterTicks = Math.max(1, c.getInt("adaptive.high-load-after-ticks", 8));
        this.normalMspt = Math.max(1.0, c.getDouble("adaptive.normal-mspt", 12.0));
        this.normalAfterTicks = Math.max(1, c.getInt("adaptive.normal-after-ticks", 40));
        this.normalMainDrain = Math.max(1, c.getInt("adaptive.normal-main-drain", 48));
        this.highLoadMainDrain = Math.max(1, c.getInt("adaptive.high-load-main-drain", 10));
        this.highLoadPathwarmMobs = Math.max(1, c.getInt("adaptive.high-load-pathwarm-mobs", 16));
        this.highLoadUseIdleRadius = c.getBoolean("adaptive.high-load-use-idle-radius", false);

        this.monitorEnabled = c.getBoolean("monitor.enabled", true);
        this.reportIntervalTicks = Math.max(0, c.getInt("monitor.report-interval-ticks", 1200));
        this.overloadedMspt = Math.max(1.0, c.getDouble("monitor.overloaded-mspt", 50.0));
        this.historySize = Math.max(10, c.getInt("monitor.history-size", 180));
        this.reportPluginCost = c.getBoolean("monitor.report-plugin-cost", true);

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
                + ", preload=" + preloadEnabled + "@r" + preloadRadiusChunks
                + ", pathwarm=" + pathwarmEnabled
                + ", adaptive=" + adaptiveEnabled + "(spread>" + (int) highLoadMspt + "ms)"
                + ", monitor=" + monitorEnabled;
    }
}
