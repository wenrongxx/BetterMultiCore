# Version 1.4.0 (tested with 4 real protocol players)

- Particle command leaf fan-out: viewers >= threshold use parallel send
- Same args / viewers / force / distance / messages as vanilla
- execute world logic stays on main thread (required)
- No AI dormancy
- Test: 4/4 players, invocations=2 parallelBatches=2, no exceptions
- Rollback: versions/1.3.0 or earlier
