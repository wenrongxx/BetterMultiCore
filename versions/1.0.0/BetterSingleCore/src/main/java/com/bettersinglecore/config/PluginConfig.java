package com.bettersinglecore.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Typed snapshot of config.yml. Reload replaces the active instance atomically.
 */
public final class PluginConfig {

    public final boolean enabled;

    public final boolean dormancyEnabled;
    public final int dormancyScanIntervalTicks;
    public final double dormantDistance;
    public final double wakeMargin;
    public final boolean disableAiFlag;
    public final int maxEntitiesPerScan;
    public final Set<String> skipTypes;
    public final boolean neverDormantIfPassenger;
    public final boolean neverDormantIfDriving;

    public final boolean preloadEnabled;
    public final int preloadIntervalTicks;
    public final int preloadRadiusChunks;
    public final int maxLoadsPerCycle;
    public final boolean generateIfMissing;
    public final int callbackBatchPerTick;

    public final boolean monitorEnabled;
    public final int reportIntervalTicks;
    public final double overloadedMspt;
    public final int historySize;

    public final boolean budgetEnabled;
    public final double maxScanMs;
    public final double maxPreloadKickMs;
    public final int aggressiveAfterOverloadedTicks;
    public final int aggressiveDormancyIntervalMultiplier;
    public final int aggressivePreloadRadiusMin;

    public final boolean logSummaryOnEnable;
    public final boolean trackDormancyStats;

    private PluginConfig(FileConfiguration c) {
        this.enabled = c.getBoolean("enabled", true);

        this.dormancyEnabled = c.getBoolean("dormancy.enabled", true);
        this.dormancyScanIntervalTicks = Math.max(1, c.getInt("dormancy.scan-interval-ticks", 40));
        this.dormantDistance = Math.max(8.0, c.getDouble("dormancy.dormant-distance", 48.0));
        this.wakeMargin = Math.max(0.0, c.getDouble("dormancy.wake-margin", 8.0));
        this.disableAiFlag = c.getBoolean("dormancy.disable-ai-flag", true);
        this.maxEntitiesPerScan = Math.max(16, c.getInt("dormancy.max-entities-per-scan", 400));
        this.skipTypes = new HashSet<>();
        List<String> skips = c.getStringList("dormancy.skip-types");
        if (skips != null) {
            for (String s : skips) {
                if (s != null && !s.isBlank()) {
                    this.skipTypes.add(s.trim().toUpperCase(java.util.Locale.ROOT));
                }
            }
        }
        this.neverDormantIfPassenger = c.getBoolean("dormancy.never-dormant-if-passenger", true);
        this.neverDormantIfDriving = c.getBoolean("dormancy.never-dormant-if-driving", true);

        this.preloadEnabled = c.getBoolean("preload.enabled", true);
        this.preloadIntervalTicks = Math.max(20, c.getInt("preload.interval-ticks", 100));
        this.preloadRadiusChunks = Math.max(0, c.getInt("preload.radius-chunks", 1));
        this.maxLoadsPerCycle = Math.max(1, c.getInt("preload.max-loads-per-cycle", 24));
        this.generateIfMissing = c.getBoolean("preload.generate-if-missing", false);
        this.callbackBatchPerTick = Math.max(1, c.getInt("preload.callback-batch-per-tick", 8));

        this.monitorEnabled = c.getBoolean("monitor.enabled", true);
        this.reportIntervalTicks = Math.max(0, c.getInt("monitor.report-interval-ticks", 1200));
        this.overloadedMspt = Math.max(1.0, c.getDouble("monitor.overloaded-mspt", 50.0));
        this.historySize = Math.max(10, c.getInt("monitor.history-size", 120));

        this.budgetEnabled = c.getBoolean("budget.enabled", true);
        this.maxScanMs = Math.max(0.2, c.getDouble("budget.max-scan-ms", 2.5));
        this.maxPreloadKickMs = Math.max(0.1, c.getDouble("budget.max-preload-kick-ms", 1.5));
        this.aggressiveAfterOverloadedTicks = Math.max(1, c.getInt("budget.aggressive-after-overloaded-ticks", 5));
        this.aggressiveDormancyIntervalMultiplier = Math.max(1, c.getInt("budget.aggressive-dormancy-interval-multiplier", 2));
        this.aggressivePreloadRadiusMin = Math.max(0, c.getInt("budget.aggressive-preload-radius-min", 0));

        this.logSummaryOnEnable = c.getBoolean("misc.log-summary-on-enable", true);
        this.trackDormancyStats = c.getBoolean("misc.track-dormancy-stats", true);
    }

    public static PluginConfig from(FileConfiguration c) {
        return new PluginConfig(c);
    }

    public double wakeDistance() {
        return Math.max(4.0, dormantDistance - wakeMargin);
    }

    public String summary() {
        return "enabled=" + enabled
                + ", dormancy=" + dormancyEnabled + "@" + dormancyScanIntervalTicks + "t/"
                + (int) dormantDistance + "m"
                + ", preload=" + preloadEnabled + "@" + preloadIntervalTicks + "t/r"
                + preloadRadiusChunks
                + ", monitor=" + monitorEnabled
                + ", budget=" + budgetEnabled;
    }
}
