# Version 1.6.0 (Spark-driven pathwarm + multi-core idle)

- Input: production spark jUnhP35Cci — MSPT p50 44ms, ~22% entity selectors, BSC ~0.1%
- pathwarm.scan-distance default 32 (one smaller AABB; no second full-radius query)
- adaptive.high-load-pathwarm-interval-multiplier default 2 (stretch scans, never pause)
- preload.idle-radius-bonus default 2 (more idle multi-core chunk warm)
- Players.all: world scan only if online+bots empty
- Still: no AI dormancy, particle fan-out, PlugMan uninstall on disable
- Rollback: versions/1.5.0
