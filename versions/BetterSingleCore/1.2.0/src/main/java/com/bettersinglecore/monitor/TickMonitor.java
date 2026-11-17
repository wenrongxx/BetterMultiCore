package com.bettersinglecore.monitor;

import com.bettersinglecore.config.PluginConfig;
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
 * MSPT ring buffer + plugin main-thread cost estimate.
 *
 * High-frequency mcfunction packs (execute/@e/particle every tick) dominate the
 * main thread; this monitor only measures and exposes load for adaptive yield.
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
    private long yieldStreak;
    private long resumeStreak;
    private long overloadedTotal;
    private long nextReportTick;

    /** Plugin main-thread work (kick/drain) accumulated this report window. */
    private final AtomicLong pluginKickNanos = new AtomicLong();
    private final AtomicLong pluginKickCount = new AtomicLong();

    private volatile boolean yielding;

    public TickMonitor(Logger log, PluginConfig config) {
        this.log = log;
        this.config = config;
    }

    public void updateConfig(PluginConfig config) {
        this.config = config;
        this.nextReportTick = 0;
    }

    /** Called by optimizers when they spend main-thread time. */
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

        // Adaptive yield state machine (command storm → yield main thread)
        if (config.adaptiveEnabled) {
            if (mspt >= config.yieldMspt) {
                yieldStreak++;
                resumeStreak = 0;
                if (!yielding && yieldStreak >= config.yieldAfterTicks) {
                    yielding = true;
                    log.info(String.format(Locale.ROOT,
                            "[BSC] Command-load yield ON (MSPT %.1f >= %.1f for %d ticks) - pausing heavy main-thread work",
                            mspt, config.yieldMspt, yieldStreak));
                }
            } else if (mspt <= config.resumeMspt) {
                resumeStreak++;
                yieldStreak = 0;
                if (yielding && resumeStreak >= config.resumeAfterTicks) {
                    yielding = false;
                    log.info(String.format(Locale.ROOT,
                            "[BSC] Command-load yield OFF (MSPT %.1f <= %.1f for %d ticks) - multi-core warmup full speed",
                            mspt, config.resumeMspt, resumeStreak));
                }
            } else {
                // between thresholds: hold state, decay streaks slowly
                if (yieldStreak > 0) {
                    yieldStreak--;
                }
                if (resumeStreak > 0) {
                    resumeStreak--;
                }
            }
        } else {
            yielding = false;
            yieldStreak = 0;
            resumeStreak = 0;
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
                    "[BSC] MSPT last=%.2f avg=%.2f max=%.2f yield=%s overloadedStreak=%d%s",
                    lastMspt, avgMsptWindow, maxMsptWindow, yielding, overloadedStreak, cost));
        }
    }

    public boolean isYielding() {
        return yielding;
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
                "MSPT last=%.2f avg=%.2f max=%.2f yield=%s streak=%d ticks=%d",
                lastMspt, avgMsptWindow, maxMsptWindow, yielding, overloadedStreak, totalTicks);
    }
}
