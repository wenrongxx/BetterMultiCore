package com.bettersinglecore;

import com.bettersinglecore.command.BscCommand;
import com.bettersinglecore.config.PluginConfig;
import com.bettersinglecore.monitor.TickMonitor;
import com.bettersinglecore.optimize.ParallelChunkEngine;
import com.bettersinglecore.optimize.PathChunkWarmer;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.logging.Logger;

/**
 * BetterSingleCore 1.1.x — multi-core offload (no AI dormancy).
 *
 * Strategy: keep the game loop on the main core; push chunk IO / generation /
 * path-neighborhood warmup onto a worker pool sized to available processors
 * so other cores do useful work in parallel.
 *
 * Explicitly does NOT disable entity AI (mob farms keep working).
 */
public final class BetterSingleCorePlugin extends JavaPlugin {

    private PluginConfig config;
    private TickMonitor monitor;
    private ParallelChunkEngine preload;
    private PathChunkWarmer pathwarm;
    private volatile boolean userEnabled = true;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = PluginConfig.from(getConfig());

        Logger log = getLogger();
        monitor = new TickMonitor(log, config);
        preload = new ParallelChunkEngine(this, log, config);
        pathwarm = new PathChunkWarmer(this, log, config);

        getServer().getPluginManager().registerEvents(monitor, this);

        PluginCommand cmd = getCommand("bsc");
        if (cmd != null) {
            BscCommand executor = new BscCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        startModules();

        if (config.logSummaryOnEnable) {
            log.info("[BSC] BetterSingleCore 1.1 multi-core enabled - " + getConfigSummary());
            log.info("[BSC] CPUs=" + Runtime.getRuntime().availableProcessors()
                    + " workers=" + config.effectiveWorkerThreads()
                    + " maxConcurrentLoads=" + config.effectiveMaxConcurrentLoads());
        }
    }

    @Override
    public void onDisable() {
        stopModules();
    }

    private void startModules() {
        if (!config.enabled || !userEnabled) {
            return;
        }
        if (config.multicoreEnabled && config.preloadEnabled) {
            preload.start();
        }
        if (config.multicoreEnabled && config.pathwarmEnabled) {
            pathwarm.start();
        }
    }

    private void stopModules() {
        preload.stop();
        pathwarm.stop();
    }

    public void reloadEverything() {
        reloadConfig();
        config = PluginConfig.from(getConfig());
        monitor.updateConfig(config);
        preload.updateConfig(config);
        pathwarm.updateConfig(config);

        stopModules();
        startModules();
        getLogger().info("[BSC] Reloaded: " + getConfigSummary());
    }

    public void setUserEnabled(boolean enabled) {
        this.userEnabled = enabled;
        if (enabled) {
            startModules();
        } else {
            stopModules();
        }
    }

    public String buildStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("§6=== BetterSingleCore 1.1 (multi-core) ===\n");
        sb.append("§7Enabled: §f").append(userEnabled && config.enabled).append('\n');
        sb.append("§7CPUs: §f").append(Runtime.getRuntime().availableProcessors())
                .append(" §7| Workers: §f").append(config.effectiveWorkerThreads())
                .append(" §7| MaxLoads: §f").append(config.effectiveMaxConcurrentLoads()).append('\n');
        sb.append("§7Config: §f").append(getConfigSummary()).append('\n');
        sb.append("§bMonitor: §f").append(monitor.statusLine()).append('\n');
        sb.append("§bPreload: §f").append(preload.statusLine()).append('\n');
        sb.append("§bPathWarm: §f").append(pathwarm.statusLine()).append('\n');
        sb.append("§7Note: AI is never disabled - mob farms are safe.");
        return sb.toString();
    }

    public String getConfigSummary() {
        return config == null ? "n/a" : config.summary();
    }

    public TickMonitor getMonitor() {
        return monitor;
    }

    public ParallelChunkEngine getPreload() {
        return preload;
    }

    public PathChunkWarmer getPathwarm() {
        return pathwarm;
    }

    public PluginConfig getPluginConfig() {
        return config;
    }

    public String metricsLine() {
        return String.format(Locale.ROOT, "bsc enabled=%s cpus=%d | %s | %s | %s",
                userEnabled && config.enabled,
                Runtime.getRuntime().availableProcessors(),
                monitor.statusLine(), preload.statusLine(), pathwarm.statusLine());
    }
}
