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
 * BetterSingleCore 1.2 — multi-core offload + command-load adaptive yield.
 *
 * High-frequency datapacks (many execute/@e/particle per tick) own the main
 * thread; this plugin:
 *  1) Yields heavy main-thread work when MSPT shows a command storm
 *  2) Uses other cores for chunk IO / path warmup when the tick is calm
 *  3) Never touches entity AI (mob farms safe)
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
        preload.setMonitor(monitor);
        pathwarm.setMonitor(monitor);

        getServer().getPluginManager().registerEvents(monitor, this);

        PluginCommand cmd = getCommand("bsc");
        if (cmd != null) {
            BscCommand executor = new BscCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        startModules();

        if (config.logSummaryOnEnable) {
            log.info("[BSC] BetterSingleCore 1.2 enabled - " + getConfigSummary());
            log.info("[BSC] CPUs=" + Runtime.getRuntime().availableProcessors()
                    + " workers=" + config.effectiveWorkerThreads()
                    + " adaptive=" + config.adaptiveEnabled
                    + " yieldMspt=" + config.yieldMspt);
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
        sb.append("§6=== BetterSingleCore 1.2 (multi-core + adaptive) ===\n");
        sb.append("§7Enabled: §f").append(userEnabled && config.enabled)
                .append(" §7| Yield: §f").append(monitor.isYielding()).append('\n');
        sb.append("§7CPUs: §f").append(Runtime.getRuntime().availableProcessors())
                .append(" §7| Workers: §f").append(config.effectiveWorkerThreads())
                .append(" §7| MaxLoads: §f").append(config.effectiveMaxConcurrentLoads()).append('\n');
        sb.append("§7Config: §f").append(getConfigSummary()).append('\n');
        sb.append("§bMonitor: §f").append(monitor.statusLine()).append('\n');
        sb.append("§bPreload: §f").append(preload.statusLine()).append('\n');
        sb.append("§bPathWarm: §f").append(pathwarm.statusLine()).append('\n');
        sb.append("§7Note: AI never disabled. Command storms pause heavy BSC work.");
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
        return String.format(Locale.ROOT, "bsc enabled=%s cpus=%d yield=%s | %s | %s | %s",
                userEnabled && config.enabled,
                Runtime.getRuntime().availableProcessors(),
                monitor.isYielding(),
                monitor.statusLine(), preload.statusLine(), pathwarm.statusLine());
    }
}
