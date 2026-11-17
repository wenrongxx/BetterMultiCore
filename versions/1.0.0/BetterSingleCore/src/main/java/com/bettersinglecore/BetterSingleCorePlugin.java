package com.bettersinglecore;

import com.bettersinglecore.command.BscCommand;
import com.bettersinglecore.config.PluginConfig;
import com.bettersinglecore.monitor.TickMonitor;
import com.bettersinglecore.optimize.AiDormancyService;
import com.bettersinglecore.optimize.ChunkPreloaderService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.logging.Logger;

/**
 * BetterSingleCore — reduce main-thread (single-core) pressure on Leaves/Paper.
 *
 * Modules:
 *  1. TickMonitor     — MSPT / overloaded streak via ServerTickStart/End
 *  2. AiDormancy      — sleep AI for mobs far from players (largest main-thread win)
 *  3. ChunkPreloader  — async warm chunks around players (flatten load spikes)
 *
 * All optimizers honor time budgets so a single scan cannot stall a tick.
 */
public final class BetterSingleCorePlugin extends JavaPlugin {

    private PluginConfig config;
    private TickMonitor monitor;
    private AiDormancyService dormancy;
    private ChunkPreloaderService preload;
    private volatile boolean userEnabled = true;
    private boolean aggressive;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = PluginConfig.from(getConfig());

        Logger log = getLogger();
        monitor = new TickMonitor(log, config);
        dormancy = new AiDormancyService(this, log, config);
        preload = new ChunkPreloaderService(this, log, config);

        getServer().getPluginManager().registerEvents(monitor, this);

        PluginCommand cmd = getCommand("bsc");
        if (cmd != null) {
            BscCommand executor = new BscCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        startOptimizers();
        startAggressiveWatcher();

        if (config.logSummaryOnEnable) {
            log.info("[BSC] BetterSingleCore enabled — " + getConfigSummary());
        }
    }

    @Override
    public void onDisable() {
        stopOptimizers();
        if (dormancy != null) {
            dormancy.wakeAll();
        }
    }

    private void startOptimizers() {
        if (!config.enabled || !userEnabled) {
            return;
        }
        if (config.dormancyEnabled) {
            dormancy.start();
        }
        if (config.preloadEnabled) {
            preload.start();
        }
    }

    private void stopOptimizers() {
        if (dormancy != null) {
            dormancy.stop();
        }
        if (preload != null) {
            preload.stop();
        }
    }

    private void startAggressiveWatcher() {
        if (!config.budgetEnabled || !config.monitorEnabled) {
            return;
        }
        int period = Math.max(20, config.dormancyScanIntervalTicks / 2);
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (!userEnabled || !config.enabled || monitor == null) {
                return;
            }
            boolean now = monitor.isOverloaded();
            if (now != aggressive) {
                aggressive = now;
                dormancy.setAggressive(now);
                preload.setAggressive(now);
                if (now) {
                    getLogger().info("[BSC] MSPT overloaded streak — entering AGGRESSIVE mode (thinner scans, smaller preload radius).");
                } else {
                    getLogger().info("[BSC] MSPT recovered — back to NORMAL mode.");
                }
            }
        }, period, period);
    }

    public void reloadEverything() {
        reloadConfig();
        config = PluginConfig.from(getConfig());
        monitor.updateConfig(config);
        dormancy.updateConfig(config);
        preload.updateConfig(config);

        stopOptimizers();
        // Re-register is unnecessary (listeners already bound to same instances).
        startOptimizers();
        getLogger().info("[BSC] Reloaded: " + getConfigSummary());
    }

    public void setUserEnabled(boolean enabled) {
        this.userEnabled = enabled;
        if (enabled) {
            startOptimizers();
        } else {
            stopOptimizers();
            dormancy.wakeAll();
        }
    }

    public String buildStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("§6=== BetterSingleCore ===\n");
        sb.append("§7Enabled: §f").append(userEnabled && config.enabled)
                .append(" §7| Aggressive: §f").append(aggressive).append('\n');
        sb.append("§7Config: §f").append(getConfigSummary()).append('\n');
        sb.append("§bMonitor: §f").append(monitor.statusLine()).append('\n');
        sb.append("§bDormancy: §f").append(dormancy.statusLine()).append('\n');
        sb.append("§bPreload: §f").append(preload.statusLine()).append('\n');
        sb.append("§7Tip: lower dormancy.distant / raise scan-interval if MSPT still high.");
        return sb.toString();
    }

    public String getConfigSummary() {
        return config == null ? "n/a" : config.summary();
    }

    public TickMonitor getMonitor() {
        return monitor;
    }

    public AiDormancyService getDormancy() {
        return dormancy;
    }

    public ChunkPreloaderService getPreload() {
        return preload;
    }

    public PluginConfig getPluginConfig() {
        return config;
    }

    /** Compact one-line English log for ops dashboards. */
    public String metricsLine() {
        return String.format(Locale.ROOT, "bsc enabled=%s aggressive=%s %s | %s | %s",
                userEnabled && config.enabled, aggressive,
                monitor.statusLine(), dormancy.statusLine(), preload.statusLine());
    }
}
