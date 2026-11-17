package com.bettermulticore.command;

import com.bettermulticore.BetterMultiCorePlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BmcCommand implements CommandExecutor, TabCompleter {

    private final BetterMultiCorePlugin plugin;

    public BmcCommand(BetterMultiCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("bettermulticore.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> sender.sendMessage(plugin.buildStatus());
            case "reload" -> {
                plugin.reloadEverything();
                sender.sendMessage("§a[BMC] Config reloaded. " + plugin.getConfigSummary());
            }
            case "enable" -> {
                plugin.setUserEnabled(true);
                sender.sendMessage("§a[BMC] Modules enabled.");
            }
            case "disable" -> {
                plugin.setUserEnabled(false);
                sender.sendMessage("§e[BMC] Modules disabled.");
            }
            case "workers" -> sender.sendMessage("§b[Workers] CPUs="
                    + Runtime.getRuntime().availableProcessors()
                    + " effective=" + plugin.getPluginConfig().effectiveWorkerThreads()
                    + " maxConcurrentLoads=" + plugin.getPluginConfig().effectiveMaxConcurrentLoads());
            case "preload" -> sender.sendMessage("§b[Preload] " + plugin.getPreload().statusLine());
            case "pathwarm" -> sender.sendMessage("§b[PathWarm] " + plugin.getPathwarm().statusLine());
            case "monitor" -> sender.sendMessage("§b[Monitor] " + plugin.getMonitor().statusLine());
            case "particle" -> sender.sendMessage("§b[Particle] " + plugin.getParticleOffload().statusLine());
            case "adaptive" -> {
                var c = plugin.getPluginConfig();
                sender.sendMessage("§b[Adaptive] enabled=" + c.adaptiveEnabled
                        + " highLoad=" + plugin.getMonitor().isHighLoad()
                        + " highLoadMspt=" + c.highLoadMspt
                        + " normalMspt=" + c.normalMspt
                        + " drain=" + (plugin.getMonitor().isHighLoad() ? c.highLoadMainDrain : c.normalMainDrain)
                        + " (spread, never pause)");
            }
            case "help" -> sender.sendMessage(String.join("\n",
                    "§6/bmc status §7- full status",
                    "§6/bmc reload §7- reload config.yml",
                    "§6/bmc enable|disable §7- toggle modules",
                    "§6/bmc workers|preload|pathwarm|monitor|particle|adaptive §7- stats"));
            default -> sender.sendMessage("§cUnknown subcommand. Try /bmc help");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String p = args[0].toLowerCase(Locale.ROOT);
            for (String s : List.of("status", "reload", "enable", "disable",
                    "workers", "preload", "pathwarm", "monitor", "particle", "adaptive", "help")) {
                if (s.startsWith(p)) {
                    out.add(s);
                }
            }
        }
        return out;
    }
}
