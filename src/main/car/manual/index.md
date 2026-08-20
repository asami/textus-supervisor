# Textus Supervisor Reference Manual

## Purpose And Scope

Textus Supervisor provides the standard CNCF `Supervisor` service for protected
lifecycle requests. It is the lifecycle authority for a managed Textus
deployment; Textus Control Center is its administrative consumer and retains
the audit record.

The component accepts opaque lifecycle intent only. Its service contract never
accepts a process identifier, shell command, filesystem locator, or credential.

## Assembly Configuration

Install Textus Supervisor together with Textus Control Center in the same
assembly. Bind Control Center's `supervisor` socket to the Supervisor provider.
The standalone Control Center assembly declares this component pairing and its
SPI binding; keep the two components at compatible released versions.

The standalone provider reads the configured Control Center home and its
provider-private catalog information to select a deployment source. Those host
details are deployment configuration, not operation inputs or a public API.

## Service Contract

`Supervisor.submit` is a protected command. Its request contains a request ID,
idempotency key, target ID, optional deployment ID, action, operator subject,
and deadline. The action is `start`, `stop`, or `restart`.

`Supervisor.lookup` is a protected query for one request ID. It returns the
durable result when known and no result when the request is unknown. It never
starts or controls a deployment.

Each result records the original request ID, state, optional diagnostic code
and diagnostic, supervisor ID, optional instance ID, and acceptance/completion
timestamps.

## Durable And Idempotent Behavior

The provider records request and ownership state before lifecycle control.
Repeating the same request ID and idempotency key returns the retained result
and does not create another managed deployment. Unknown, corrupt, or stale
ownership fails closed; ownership is never reconstructed from a PID, command
line, or occupied port.

The standalone implementation's local process, host filesystem, readiness,
and diagnostic handling are provider/bootstrap responsibilities. They are
deliberately outside the component operation contract and are not an alternate
consumer control surface.

## Failures And Limits

An unconfigured driver, unavailable launch profile, unavailable execution,
occupied declared port, failed readiness check, or unavailable durable state is
returned as a structured lifecycle result. The service does not expose host
paths, child-process handles, shell syntax, or credentials in its contract.

For operator workflow and troubleshooting, see the [Textus Supervisor User
Guide](user-guide.md).
