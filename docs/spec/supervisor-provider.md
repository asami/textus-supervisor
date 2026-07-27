# Textus Supervisor Provider

`textus-supervisor` provides CNCF's `Supervisor` SPI. It accepts only the
opaque `SupervisorRequest` contract and returns the matching
`SupervisorResult`; it never accepts a PID, shell command, filesystem locator,
or credential from a consumer.

For standalone use, the provider is assembled beside Textus Control Center.
For distributed use, the same provider contract can select a Compose or
Kubernetes driver. Driver selection and deployment ownership remain inside the
provider.

The provider will persist request and ownership state before starting or
controlling any deployment. Repeating a request ID and idempotency key will
return the retained result and never create another managed deployment.

The standalone provider retains a versioned request/result and ownership
snapshot below the configured Control Center home. It writes a replacement
snapshot before starting a child and replaces the durable file atomically when
the host filesystem supports it. Unknown, corrupt, or stale ownership fails
closed; the provider never reconstructs ownership from a PID, command line, or
occupied port. External lifecycle authority remains the opaque Supervisor SPI.
