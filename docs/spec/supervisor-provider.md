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

## External placement selection

The same configured provider may delegate to an external Supervisor Service.
When all three external keys below are absent, the existing local selection is
unchanged. Presence of any external key selects external mode, including an
empty value or an invalid object/list value. An incomplete or invalid explicit
selection produces a structured unavailable backend behind the same application
Service. It never creates a local standalone backend, registers local launch
profiles, or falls back to local execution. A configured Control Center home
may coexist as consumer storage; it confers no remote ownership authority.

| Key | Contract |
| --- | --- |
| `textus-supervisor.external.endpoint` | Required external origin. |
| `textus-supervisor.external.authentication.token` | Required outgoing service token. |
| `textus-supervisor.external.timeout` | Optional ISO duration, default `PT5S`. |
| `textus-supervisor.service.authentication.token` | Independent optional incoming service token. |
| `textus-supervisor.service.authentication.principal-id` | Incoming service principal, default `textus-supervisor-control-center`. |

These exact keys use normal `ConfigurationAccess` resolution. There are no
additional environment aliases or token provisioning. An endpoint is an
absolute HTTPS origin with a nonempty host, or HTTP with the literal host
`127.0.0.1` or `[::1]`. DNS names such as `localhost` are not an HTTP exception.
The path is empty or `/`; user information, query and fragment are forbidden.
Tokens are trimmed, nonempty and contain no whitespace or control characters.
The timeout must have integral milliseconds from 1 through 30000 inclusive;
nonpositive, sub-millisecond, overflowing and larger values are invalid.
Configuration and provider representations redact credentials and endpoints.

## Trusted service authentication

The primary component installs the existing `AuthenticationProvider` named
`textus-supervisor-service` only for valid explicit incoming token settings.
It compares UTF-8 token bytes with `MessageDigest.isEqual`. Missing, invalid or
mismatched credentials return a non-match. Invalid explicit incoming principal
configuration leaves authentication unavailable; it never silently chooses a
different authority. An explicit principal is a trimmed, nonempty public
principal ID of at most 64 characters without control characters.

A matching incoming token establishes `SubjectKind.Service`, security level
`service`, and exactly the `Operator.name` capability. Normal ingress provider
resolution establishes authentication provenance. Outgoing credentials,
Launcher registration credentials, `operatorSubjectId`, request claims and
privilege headers do not establish this caller authority. Incoming and outgoing
configuration are independent. The existing shared `TextusSupervisorService`
guard remains the one administrative guard for both the installed SPI and the
generated Operations, before backend access or outbound HTTP. Represented
operator identity remains audit data and may differ from the caller.

## External operation transport and results

The external adapter invokes the existing generated routes:

- `POST /rest/v1/org.simplemodeling.textus.Supervisor/Supervisor/submit`;
- `GET /rest/v1/org.simplemodeling.textus.Supervisor/Supervisor/lookup?requestId=<UTF-8 URL-encoded ID>`.

Both carry `textus.format=json`, `textus.output.shape=data`, and
`textus.command.execution-mode=sync` as normal framework query settings,
`Authorization: Bearer <outgoing token>` and `Accept: application/json`.
Submit also carries `Content-Type: application/json`. Its JSON is exactly the
existing request fields, with lowercase action and ISO instant deadline; no
credential or provider-private process data is added.

The adapter constructs only `UnitOfWorkOp.HttpPost` and `HttpGet`, executed by
the current `ExecutionContext.unitOfWorkInterpreter`. Every operation supplies
`http.total-timeout-ms`, `http.max-response-bytes=32768`, and
`http.follow-redirects=false`. Runtime-selected `HttpDriver` owns transport,
CallTree, cancellation, whole-exchange deadline, finite materialization and
test substitution. Submit uses the lesser of configured timeout and positive
remaining request deadline, truncating to milliseconds without rounding up.
Expired and sub-millisecond remaining deadlines fail before dispatch. Lookup
uses the configured timeout. There is no application retry or local fallback.

Only status 200 is decoded. Status 401 or 403 becomes
`securityPermissionDenied`; all other statuses, redirects and transport or
timeout failures become `serviceUnavailable`. Empty, malformed, mistyped,
missing-required, overlength, unknown-state, invalid-instant or mismatched-ID
responses become `operationInvalid`. These failures use constant safe
diagnostics without retaining the remote body, endpoint, token, exception or
original Conclusion. No synthetic successful failure result is constructed.
The codec preserves existing typed result fields and optionals within CML
constraints. Lookup requires an object wrapper; absent or null `result` means
`None`, and a present result must echo the requested ID. A response cannot
update evidence belonging to another request.

## Executable specifications

[ExternalTextusSupervisorSpec](../../src/test/scala/org/simplemodeling/textus/supervisor/impl/ExternalTextusSupervisorSpec.scala)
specifies explicit production selection and authenticator wiring (XP1),
credential admission (XP2), actual UoW driver substitution (XP3), bounded
synthetic wire failures (XP6), and at least 50 successful generated correlation
round trips with zero discards (XP7).
[ExternalSupervisorServiceSpec](../../src/test/scala/org/simplemodeling/textus/supervisor/impl/ExternalSupervisorServiceSpec.scala)
specifies normal authenticated ingress (XP2), actual generated HTTP Operations
and durable idempotency (XP4), and reconstructed remote request evidence with
unproven ownership rejected (XP5). Its recording process host replaces only
the OS child boundary. These specifications do not stand in for canonical-child,
packaged placement, panel, or complete Phase acceptance.
