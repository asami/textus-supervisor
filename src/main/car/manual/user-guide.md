# Textus Supervisor User Guide

## Prerequisites

Deploy Textus Supervisor and Textus Control Center in the same assembly, with
Control Center's `supervisor` socket bound to this provider. Configure the
standalone provider with the Control Center home and the catalog information
that identifies permitted deployment sources. Keep source selection and any
provider credentials in deployment configuration rather than lifecycle
requests.

## First Successful Lifecycle Route

Use Textus Control Center's normal administrative flow. Control Center creates
the protected lifecycle audit record and sends its request through the
assembly-bound Supervisor socket. The provider selects the configured source,
records ownership and request state, and then performs the requested lifecycle
action.

Do not attempt to start a managed deployment through a direct host process,
PID, filesystem location, or shell command. Those are not user contracts and
cannot safely establish durable ownership.

## Normal Lifecycle And Lookup Workflow

1. Submit one protected request through Control Center with a new request ID
   and idempotency key.
2. Read the returned state and retain the request ID.
3. Use the protected lookup operation with that request ID to observe the
   durable result without creating more lifecycle work.
4. Reuse the same request ID and idempotency key only when retrying the same
   intent; the provider returns the retained result instead of creating another
   managed deployment.

Use `start`, `stop`, or `restart` as the requested action. A stop or restart
still follows the same durable request/result handling.

## Troubleshooting

- A driver-unconfigured or launch-profile diagnostic means the assembly lacks
  the required deployment configuration; correct the assembly configuration
  and submit a new request.
- An execution-unavailable, port-unavailable, or readiness diagnostic means
  the provider could not safely control the selected deployment. Use lookup to
  retain the diagnostic, then correct the deployment environment before a new
  request.
- A state-unavailable diagnostic means durable request/ownership state cannot
  be used safely. Treat the lifecycle operation as failed closed; do not infer
  ownership from a running process or occupied port.
- An unknown lookup result means no durable result exists for that request ID;
  it does not imply a deployment was started.

See the [Textus Supervisor Reference Manual](index.md) for service semantics
and failure limits.
