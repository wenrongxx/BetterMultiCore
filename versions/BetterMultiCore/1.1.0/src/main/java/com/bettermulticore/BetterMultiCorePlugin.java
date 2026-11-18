package com.bettermulticore;

import com.bettermulticore.command.BmcCommand;
import com.bettermulticore.config.PluginConfig;
import com.bettermulticore.monitor.TickMonitor;
import com.bettermulticore.optimize.ParallelChunkEngine;
import com.bettermulticore.optimize.ParticleCommandOffload;
import com.bettermulticore.optimize.PathChunkWarmer;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.logging.Logger;

/**
 * BetterMultiCore 1.0 — multi-core spread + parallel particle + lighter pathwarm.
 *
 * - Never pauses preload/pathwarm; high MSPT shrinks budgets / stretches scan interval.
 * - Pathwarm uses a smaller discovery AABB (scan-distance) — one query, no double full-radius scan.
 * - /particle leaf commands fan out per-player sends across workers (vanilla semantics).
 * - execute world-state logic stays on the main thread (required for vanilla correctness).
 * - Entity AI is never disabled.
 * - onDisable restores vanilla /particle (PlugMan / disablePlugin safe).
 */
public final class BetterMultiCorePlugin extends JavaPlugin {

    private PluginConfig config;
    private TickMonitor monitor;
    private ParallelChunkEngine preload;
    private PathChunkWarmer pathwarm;
    private ParticleCommandOffload particleOffload;
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

        particleOffload = new ParticleCommandOffload(
                this, log,
                config.enabled && config.particleParallelEnabled,
                config.particleParallelViewersThreshold,
                config.particleParallelMinWork);

        getServer().getPluginManager().registerEvents(monitor, this);

        PluginCommand cmd = getCommand("bmc");
        if (cmd != null) {
            BmcCommand executor = new BmcCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        startModules();
        particleOffload.installDelayed();

        if (config.logSummaryOnEnable) {
            log.info("[BMC] BetterMultiCore 1.1 enabled - " + getConfigSummary());
            log.info("[BMC] CPUs=" + Runtime.getRuntime().availableProcessors()
                    + " workers=" + config.effectiveWorkerThreads()
                    + " particleParallel=" + config.particleParallelEnabled
                    + " (execute stays on main thread)");
        }
    }

    @Override
    public void onDisable() {
        stopModules();
        // Critical for PlugMan: drop Brigadier patches so this ClassLoader can be GC'd.
        if (particleOffload != null) {
            particleOffload.uninstall();
        }
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

        // Re-apply particle patch with new threshold/enabled flag (hot-safe).
        boolean particleWanted = config.enabled && config.particleParallelEnabled;
        if (particleOffload != null) {
            particleOffload.uninstall();
        }
        particleOffload = new ParticleCommandOffload(
                this, getLogger(),
                particleWanted,
                config.particleParallelViewersThreshold,
                config.particleParallelMinWork);
        if (particleWanted) {
            particleOffload.installDelayed();
        }
        getLogger().info("[BMC] Reloaded: " + getConfigSummary());
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
        sb.append("§6=== BetterMultiCore 1.1 (idle economy + particle min-work) ===\n");
        sb.append("§7Enabled: §f").append(userEnabled && config.enabled)
                .append(" §7| HighLoad: §f").append(monitor.isHighLoad()).append('\n');
        sb.append("§7CPUs: §f").append(Runtime.getRuntime().availableProcessors())
                .append(" §7| Workers: §f").append(config.effectiveWorkerThreads())
                .append(" §7| MaxLoads: §f").append(config.effectiveMaxConcurrentLoads()).append('\n');
        sb.append("§7Config: §f").append(getConfigSummary()).append('\n');
        sb.append("§bMonitor: §f").append(monitor.statusLine()).append('\n');
        sb.append("§bPreload: §f").append(preload.statusLine()).append('\n');
        sb.append("§bPathWarm: §f").append(pathwarm.statusLine()).append('\n');
        sb.append("§bParticle: §f").append(particleOffload.statusLine()).append('\n');
        sb.append("§7Note: execute world logic on main; particle send fan-out parallel.");
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

    public ParticleCommandOffload getParticleOffload() {
        return particleOffload;
    }

    public PluginConfig getPluginConfig() {
        return config;
    }

    public String metricsLine() {
        return String.format(Locale.ROOT, "bmc enabled=%s cpus=%d highLoad=%s | %s | %s | %s | %s",
                userEnabled && config.enabled,
                Runtime.getRuntime().availableProcessors(),
                monitor.isHighLoad(),
                monitor.statusLine(), preload.statusLine(), pathwarm.statusLine(),
                particleOffload.statusLine());
    }
}
