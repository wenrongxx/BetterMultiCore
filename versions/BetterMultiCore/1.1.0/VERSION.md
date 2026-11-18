# BetterMultiCore 1.1.0

- Idle economy: stretch preload/pathwarm when MSPT < idle-mspt and last cycle had no work (drain never stops)
- Particle: sequential when viewers*count < parallel-min-work (default 64)
- Docs: docs/v1.1/ (diagnosis, design, bench)
