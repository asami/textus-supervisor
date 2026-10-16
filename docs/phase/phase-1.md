# Phase 1: Docker Execution Provider

Status: PLANNED
Planned: 2026-10-16
Consumer: textus-control-center Phase 8
Upstream: goldenport-cncf Phase 113
Related: textus-control-center Phase 4 lifecycle authority

## Goal

Make Textus Supervisor the lifecycle authority for CARs deployed in the Docker Compose operational profile while preserving the command/result and ownership contract established by Control Center Phase 4.

## Scope

- Define the Supervisor execution-boundary/provider abstraction.
- Implement the Docker execution provider.
- Support authorized start, stop, restart and status for Supervisor-owned CAR containers.
- Persist/restore runtime-instance ownership needed for safe lifecycle operations.
- Expose bounded Supervisor identity, execution-boundary kind, capabilities and connectivity/status to management consumers.
- Accept the existing typed lifecycle request/result protocol from Textus Control Center.
- Correlate lifecycle results with the resulting Launcher registration/evidence instance.
- Support external service placement in the same Compose network as Control Center.
- Add executable acceptance for container start, normal stop, restart, stale ownership, Supervisor restart and failure isolation.

## Authority

Textus Supervisor owns physical lifecycle execution. Textus Control Center owns the operator-facing operational model and submits authorized typed requests. Launchers own invocation evidence and registration/heartbeat/deregistration. CNCF Subsystems own detailed runtime health/readiness.

## Docker boundary

- Docker daemon access belongs only to the Docker execution provider.
- Control Center must not require Docker socket access.
- Launchers must not become Docker API clients.
- A runtime instance has exactly one owning Supervisor/execution boundary.
- Docker container state is not CNCF application health.

## Security

- Use the minimum Docker authority required by the accepted provider implementation.
- Do not project Compose secrets, Tunnel tokens, unrestricted environment values or arbitrary Docker metadata.
- Do not discover/control arbitrary containers merely because they exist on the host.
- Lifecycle operations apply only to explicitly Supervisor-managed targets.

## Acceptance

From a separately deployed Supervisor service, Control Center can submit an authorized lifecycle request for a managed CAR, the Docker provider executes it, the resulting CAR follows the canonical Launcher registration path, and the correlated result is visible without granting Docker authority to Control Center.

## Future

A native/host execution provider will be a later phase using the same execution-boundary contract.
