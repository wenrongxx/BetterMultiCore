package com.bettersinglecore.command;

import com.bettersinglecore.BetterSingleCorePlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BscCommand implements CommandExecutor, TabCompleter {

    private final BetterSingleCorePlugin plugin;

    public BscCommand(BetterSingleCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("bettersinglecore.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> sender.sendMessage(plugin.buildStatus());
            case "reload" -> {
                plugin.reloadEverything();
                sender.sendMessage("§a[BSC] Config reloaded. " + plugin.getConfigSummary());
            }
            case "enable" -> {
                plugin.setUserEnabled(true);
                sender.sendMessage("§a[BSC] Multi-core modules enabled.");
            }
            case "disable" -> {
                plugin.setUserEnabled(false);
                sender.sendMessage("§e[BSC] Multi-core modules disabled.");
            }
            case "workers" -> sender.sendMessage("§b[Workers] CPUs="
                    + Runtime.getRuntime().availableProcessors()
                    + " effective=" + plugin.getPluginConfig().effectiveWorkerThreads()
                    + " maxConcurrentLoads=" + plugin.getPluginConfig().effectiveMaxConcurrentLoads());
            case "preload" -> sender.sendMessage("§b[Preload] " + plugin.getPreload().statusLine());
            case "pathwarm" -> sender.sendMessage("§b[PathWarm] " + plugin.getPathwarm().statusLine());
            case "monitor" -> sender.sendMessage("§b[Monitor] " + plugin.getMonitor().statusLine());
            case "help" -> sender.sendMessage(String.join("\n",
                    "§6/bsc status §7- full status",
                    "§6/bsc reload §7- reload config.yml",
                    "§6/bsc enable|disable §7- toggle multi-core modules",
                    "§6/bsc workers §7- CPU / pool size",
                    "§6/bsc preload|pathwarm|monitor §7- module stats"));
            default -> sender.sendMessage("§cUnknown subcommand. Try /bsc help");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String p = args[0].toLowerCase(Locale.ROOT);
            for (String s : List.of("status", "reload", "enable", "disable",
                    "workers", "preload", "pathwarm", "monitor", "help")) {
                if (s.startsWith(p)) {
                    out.add(s);
                }
            }
        }
        return out;
    }
}
