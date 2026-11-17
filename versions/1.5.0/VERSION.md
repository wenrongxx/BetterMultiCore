# Version 1.5.0 (PlugMan hot unload/load)

- Particle Brigadier patch: save vanilla Command on install, restore on uninstall
- onDisable always uninstalls particle offload (ClassLoader-safe for PlugMan)
- reloadEverything rebuilds particle offload with new config
- status: `installed= leaves= originals= invocations=`
- Tested: PlugManX 3.0.4, 3× unload+load cycles, FAIL_COUNT=0, no ClassNotFoundException
- Commands: `/plugman unload|load|reload BetterSingleCore`
- Rollback: versions/1.4.0 (no uninstall — do not PlugMan-unload)
