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
import java.util.logging.Logger;

/**
 * MSPT ring buffer via Paper tick start/end. Cheap and read-only for diagnostics.
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
    private long overloadedTotal;
    private long nextReportTick;

    public TickMonitor(Logger log, PluginConfig config) {
        this.log = log;
        this.config = config;
    }

    public void updateConfig(PluginConfig config) {
        this.config = config;
        this.nextReportTick = 0;
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
            log.info(String.format(Locale.ROOT,
                    "[BSC] MSPT last=%.2f avg=%.2f max=%.2f overloadedStreak=%d",
                    lastMspt, avgMsptWindow, maxMsptWindow, overloadedStreak));
        }
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

    public long getTotalTicks() {
        return totalTicks;
    }

    public String statusLine() {
        return String.format(Locale.ROOT,
                "MSPT last=%.2f avg=%.2f max=%.2f overloadedStreak=%d ticks=%d",
                lastMspt, avgMsptWindow, maxMsptWindow, overloadedStreak, totalTicks);
    }
}
