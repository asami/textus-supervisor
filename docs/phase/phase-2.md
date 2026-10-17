# Phase 2: Native Host Execution Provider

Status: PLANNED
Planned: future
Depends on: Phase 1
Consumer: textus-control-center native-host operational extension

## Goal

Add a native host execution boundary for explicitly managed services that cannot or should not run inside Docker, reusing the Supervisor contract proven by the Docker provider.

## Scope direction

- Run Textus Supervisor outside Docker as a host execution authority.
- Support explicitly managed native CAR/process lifecycle.
- Expose host Supervisor identity, capabilities and connectivity.
- Preserve unique runtime-instance lifecycle ownership.
- Add capability-bounded launchd integration only where required.
- Keep Control Center in Docker and communicate through the same typed Supervisor protocol.

## Boundaries

- No arbitrary PID discovery or process signalling.
- No broad host automation.
- Docker remains the default Mac mini OPS deployment boundary.
