package com.bettermulticore.monitor;

import com.bettermulticore.config.PluginConfig;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * MSPT monitor + high-load detector.
 *
 * High-load does NOT pause optimizers; it only signals modules to shrink
 * main-thread batch sizes so more work stays on worker cores.
 */
public final class TickMonitor implements Listener {

    private final Logger log;
    private volatile PluginConfig config;

    private long tickStartNanos;
    private boolean inTick;

    private final Deque<Double> history = new ArrayDeque<>();
    private double lastMspt;
    private double maxMsptWindow;
    private double avgMsptWindow;
    private long totalTicks;
    private long overloadedStreak;
    private long highLoadStreak;
    private long normalStreak;
    private long overloadedTotal;
    private long nextReportTick;

    private final AtomicLong pluginKickNanos = new AtomicLong();
    private final AtomicLong pluginKickCount = new AtomicLong();

    private volatile boolean highLoad;

    public TickMonitor(Logger log, PluginConfig config) {
        this.log = log;
        this.config = config;
    }

    public void updateConfig(PluginConfig config) {
        this.config = config;
        this.nextReportTick = 0;
    }

    public void recordPluginMainThreadWork(long nanos, int ops) {
        if (nanos > 0) {
            pluginKickNanos.addAndGet(nanos);
        }
        if (ops > 0) {
            pluginKickCount.addAndGet(ops);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickStart(ServerTickStartEvent event) {
        if (!config.monitorEnabled) {
            return;
        }
        tickStartNanos = System.nanoTime();
        inTick = true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ServerTickEndEvent event) {
        if (!config.monitorEnabled || !inTick) {
            inTick = false;
            return;
        }
        inTick = false;
        double mspt = (System.nanoTime() - tickStartNanos) / 1_000_000.0;
        lastMspt = mspt;
        totalTicks++;

        history.addLast(mspt);
        while (history.size() > config.historySize) {
            history.removeFirst();
        }

        if (mspt >= config.overloadedMspt) {
            overloadedStreak++;
            overloadedTotal++;
        } else {
            overloadedStreak = 0;
        }

        if (config.adaptiveEnabled) {
            if (mspt >= config.highLoadMspt) {
                highLoadStreak++;
                normalStreak = 0;
                if (!highLoad && highLoadStreak >= config.highLoadAfterTicks) {
                    highLoad = true;
                    log.info(String.format(Locale.ROOT,
                            "[BMC] High-load SPREAD mode ON (MSPT %.1f >= %.1f) - shrink main-thread batches, keep workers running",
                            mspt, config.highLoadMspt));
                }
            } else if (mspt <= config.normalMspt) {
                normalStreak++;
                highLoadStreak = 0;
                if (highLoad && normalStreak >= config.normalAfterTicks) {
                    highLoad = false;
                    log.info(String.format(Locale.ROOT,
                            "[BMC] High-load SPREAD mode OFF (MSPT %.1f <= %.1f) - full main-thread batches",
                            mspt, config.normalMspt));
                }
            } else {
                if (highLoadStreak > 0) {
                    highLoadStreak--;
                }
                if (normalStreak > 0) {
                    normalStreak--;
                }
            }
        } else {
            highLoad = false;
            highLoadStreak = 0;
            normalStreak = 0;
        }

        double sum = 0;
        double max = 0;
        for (double v : history) {
            sum += v;
            if (v > max) {
                max = v;
            }
        }
        avgMsptWindow = history.isEmpty() ? 0 : sum / history.size();
        maxMsptWindow = max;

        int interval = config.reportIntervalTicks;
        if (interval > 0 && event.getTickNumber() >= nextReportTick) {
            nextReportTick = event.getTickNumber() + interval;
            double pluginMs = pluginKickNanos.getAndSet(0) / 1_000_000.0;
            long ops = pluginKickCount.getAndSet(0);
            String cost = config.reportPluginCost
                    ? String.format(Locale.ROOT, " pluginKick=%.3fms ops=%d", pluginMs, ops)
                    : "";
            log.info(String.format(Locale.ROOT,
                    "[BMC] MSPT last=%.2f avg=%.2f max=%.2f highLoad=%s overloadedStreak=%d%s",
                    lastMspt, avgMsptWindow, maxMsptWindow, highLoad, overloadedStreak, cost));
        }
    }

    public boolean isHighLoad() {
        return highLoad;
    }

    /** @deprecated use {@link #isHighLoad()} */
    public boolean isYielding() {
        return highLoad;
    }

    public double getLastMspt() {
        return lastMspt;
    }

    public double getAvgMspt() {
        return avgMsptWindow;
    }

    public double getMaxMspt() {
        return maxMsptWindow;
    }

    public long getOverloadedStreak() {
        return overloadedStreak;
    }

    public long getOverloadedTotal() {
        return overloadedTotal;
    }

    public long getTotalTicks() {
        return totalTicks;
    }

    public String statusLine() {
        return String.format(Locale.ROOT,
                "MSPT last=%.2f avg=%.2f max=%.2f highLoad=%s streak=%d ticks=%d",
                lastMspt, avgMsptWindow, maxMsptWindow, highLoad, overloadedStreak, totalTicks);
    }
}
