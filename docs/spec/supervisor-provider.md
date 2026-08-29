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
the host filesystem supports it. After an embedded Supervisor restart, its
standalone process host may consume the Launcher-owned local server-evidence
store as a privileged recovery input. Live recovery requires an exact instance
ID, artifact, execution mode, development directory, PID, and process start
instant; a profile match alone may only clear authoritative stopped evidence
and can never create a kill-capable child handle. Unknown, corrupt, ambiguous,
or mismatched ownership fails closed. External lifecycle authority remains the
opaque Supervisor SPI, and no PID or host evidence enters that public contract.
