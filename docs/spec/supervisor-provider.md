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

## Canonical real-process Service acceptance

The explicit forked, serial `canonicalProcess` configuration contains
[CanonicalSupervisorProcessSpec](../../src/canonicalProcess/scala/org/simplemodeling/textus/supervisor/impl/CanonicalSupervisorProcessSpec.scala).
Ordinary `Test` discovery and `Test/test` remain unchanged. The configuration
uses the actual `Test/fullClasspath` for existing public context and HTTP
runtime-admission fixtures and prepends `target/canonical-process/bin` to its
forked PATH. Selected execution requires these absolute JVM properties:

| Property | Required input |
| --- | --- |
| `textus.supervisor.canonical.launcher-classpath-file` | Actual current CNCF Launcher `Runtime / fullClasspath` export. |
| `textus.supervisor.canonical.component-classpath-file` | Actual current Supervisor `Runtime / fullClasspath` export. |
| `textus.supervisor.canonical.runtime-root` | Admitted CNCF source checkout with prepared `target/cncf.d/runtime-classpath.txt`. |
| `textus.supervisor.canonical.component-root` | Actual admitted Supervisor development checkout. |

Before selecting `canonicalProcess:testOnly
org.simplemodeling.textus.supervisor.impl.CanonicalSupervisorProcessSpec`,
prepare the CNCF runtime through its existing `cncfRuntimeClasspathFile` task
and compile/export the selected Launcher. Create Supervisor's
`target/canonical-process/tmp`, then run its existing `cozyPrepareRuntime` with
the accepted CNCF, sbt-cozy, prepared Cozy and core source bindings and
`-J-Djava.io.tmpdir=target/canonical-process/tmp` from the Supervisor checkout.
Keep the relative spelling because SBT derives its boot socket path from this
property; a long absolute checkout path can exceed the native Unix-domain
socket limit. For this preparation and the following representative and
accumulator commands, also supply
`-J-Dswoval.tmpdir=<absolute Supervisor checkout>/target/canonical-process/tmp/swoval`,
replacing `<absolute Supervisor checkout>` with the admitted absolute checkout
path. macOS native monitoring library loading requires an absolute path to the
extracted library. Both properties keep their outputs in the existing Supervisor
`target/canonical-process/tmp` tree. Export its actual `Runtime / fullClasspath`
afterward. This is normal native build
preparation, without publication. The native bridge generates these existing
inputs from the unchanged real `project.yaml` and source ABI:

- `target/cncf.d/runtime-classpath.txt`;
- `target/cncf.d/car-runtime-manifest.json`;
- `target/cncf.d/component-descriptor.json`;
- existing `src/main/car/abi-manifest.json`.

Both exported and native component classpaths must contain the genuine
own-target `org/simplemodeling/textus/supervisor/impl/ComponentFactory.class`.
Missing, empty or invalid inputs and unavailable native process start metadata
fail the selected specification with prerequisite diagnostics. Fixtures do not
run builds, copy or symlink source/classes/ABI, create project descriptors,
rewrite native evidence, manufacture manifests or implement integrity checks.
Unchanged CNCF production admission consumes the native prepared contract.

Each case explicitly registers the existing public typed
`StandaloneDevelopmentLaunchProfile("textus-supervisor", componentRoot, port,
developmentRepositoryDirectories = Vector(componentRoot))` using its own free
positive port and the actual admitted checkout. This acceptance does not claim
automatic descriptor or default-port discovery. Unique case work under
`target/canonical-process/work/<unique>` contains its home, state, temporary
directory, local data, normal runtime config and assembly descriptor. The
assembly selects only canonical `org.simplemodeling.textus.Supervisor` version
`0.1.1-SNAPSHOT` with a standalone local operator. The separate runtime config
sets `textus.assembly.descriptor`, `textus.subsystem.file` and
`textus.local-data.root` to case-owned paths.

The serial wrapper executes the actual Java binary and exported Launcher
classpath, supplies case-scoped JVM home/tmp plus normal `--launcher-home`,
`--runtime-dev-dir` and `--cncf-config`, and forwards every original argument
unchanged. The real System host constructs the target-first `cncf <actual
checkout> server` command with the chosen port, opaque registration instance
and explicit development repository. No synthetic runtime or child replaces
this route. Parent fork-JVM `user.home` is scoped to the same case home for
unchanged `System.recover` and restored afterward; HOME, shell profiles and
global caches are unchanged.

CP1 requires at least three successful generated complete real lifecycles with
zero discarded cases for each of embedded and external placement: Start and
retry, actual safe Launcher list and private native correlation, backend and
consumer reconstruction, retained lookup/retry, Restart and retry, then Stop
and retry. It observes the real TCP port and native child liveness, distinct
replacement identity, and normal stopped evidence. CP2 holds a real conflicting
socket and preserves the durable rejection after release. CP3 uses the shared
administrative guard and each placement's existing expired-request taxonomy;
neither attempt may launch or create Launcher evidence. CP4 deliberately throws
a known primary exception after a genuine accepted Start in each placement.
The same exception must escape fixture cleanup, failed case files must remain,
and private evidence must show no live native invocation with the selected
port released. All eight scenarios remain required; CP4 does not replace any
of the three successful generated CP1 lifecycles per placement.

Supervisor-generated instance IDs are opaque. Both canonical Launchers reuse
the nonempty trimmed internal registration identity in their single evidence
and registration report, without requiring UUID shape. The exact internal
metadata option is withheld from runtime arguments. Empty or absent metadata
retains the Launcher's fresh UUID behavior.

Embedded entry uses the shared Service SPI. External entry uses the production
provider, normal `UrlConnectionHttpDriver`/UoW, actual generated Operations and
resource-managed `SupervisorHttpRouteFixture` with runtime admission and the
real Service authenticator. It uses `PT30S` external bounds and positive
90-second request deadlines, retaining the production 15-second readiness
bound. Reconstruction closes and reopens HTTP resources around a new backend
with the same durable state/profile.

Finally, exact backend ownership/native evidence remains the recovery input
for stopping case children. The fixture's process-host decorator delegates
real System preflight, start and recover unchanged and records only actual
child handles returned by System start for that case. Cleanup first attempts
the existing exact-ID System recovery, then in a finally block attempts to
stop every still-live originally acquired handle and checks every outcome,
even if recovery or an earlier cleanup action fails. This case-local resource
ownership never supplies a handle to production recover or replaces its
evidence-based reconstruction. Standard resource handling preserves a primary
scenario exception and suppresses secondary cleanup exceptions. CP1 failure
diagnostics include the actual ScalaCheck status and case clue.

HTTP listeners and sockets close, scoped home restores in the outer finally,
and successful case work is deleted only after confirmed shutdown. Failure or
uncertain cleanup retains case work and diagnostics. Shared checkout, compiled
outputs, ABI, native manifests and classpath are never case-owned cleanup.
There is no process scan or arbitrary PID signalling.

This proves durable genuine-child recovery at the Service/process boundary.
Restarted packaged Control Center/Supervisor JVMs, actual Web panel actions,
both packaged deployment assemblies, Textus repository launch and complete
Phase acceptance remain successor requirements.
