# Textus Supervisor Provider Design

`textus-supervisor` is a CNCF CAR that publishes the standard
`org.goldenport.cncf.spi.supervisor.Supervisor` SPI. It is not a Launcher
subcommand and does not expose a Launcher-owned configuration file as its
authority state.

The provider is responsible for lifecycle work and durable ownership. Control
Center remains the administrative consumer: it creates a protected audit record
and invokes its assembly-bound Supervisor socket. CNCF Launcher and Textus
Launcher retain common evidence independently, then notify Control Center on a
best-effort basis.

The first provider driver is standalone-local. Compose and Kubernetes drivers
are later provider-private implementations of the same SPI, not new Control
Center or Launcher interfaces.

The standalone driver uses a versioned provider-private snapshot for durable
request results and ownership reservation. The snapshot is an implementation
detail behind the Service and SPI; it is not a Control Center entity or public
CRUD surface. A later distributed driver may replace this store without
changing the public lifecycle boundary.

## External Service adapter responsibility

Explicit external placement selects a provider-private adapter behind the same
`TextusSupervisorService`. The default local branch and the public
`configured`/`configuredRuntime` signatures and `Runtime(service, provider)`
remain unchanged. Any explicit external selector commits selection to that
branch. Invalid configuration yields an unavailable backend rather than local
ownership. The external configure callback is a no-op, so local profile
registration and standalone state creation cannot become fallback behavior.

The adapter maps the existing Supervisor request/result to the generated
Service Operations. It owns JSON field and correlation interpretation, safe
failure mapping, and selection of the configured/request deadline bound. It
owns no child, process discovery, deployment state, retry policy, or separate
HTTP runtime. The remote standalone provider retains durable lifecycle
ownership and remains responsible for safe recovery.

Outbound work is only the normal current-context UoW HTTP algebra. The runtime
selects the HTTP driver and owns observability, cancellation, the complete-body
deadline, response byte ceiling and test substitution. The provider-private
codec has no transport or lifecycle authority. Original transport Conclusions,
exceptions and wire bodies are discarded when a safe structured adapter
failure is produced. This keeps a failed external call from creating or
controlling local processes or updating a different request.

The primary component exposes the ordinary service authenticator through
`authenticationProviders`; it does not add a componentlet or another Service.
Normal ingress resolves its configured incoming credential and establishes
trusted Service provenance. The outgoing credential is configured separately.
The one shared application Service guard authorizes SPI and generated
Operations before backend access. The represented operator remains distinct
audit data. No raw caller credential is forwarded and no body or header claim
is an authentication shortcut.

Downstream HTTP specifications use resource-managed Ember around the actual
package-visible `Http4sHttpServer.routes` and `HttpExecutionEngine`, with
explicit `RuntimeBindingAdmissionFixture.admit`. Their descriptor selects
MultiUser/Authenticated execution, the component-owned provider binding and
disabled privilege fallback. Only the OS child boundary is recorded. Driver
substitution, synthetic wire-failure peers and actual generated-Service
integration each specify their own boundary; none claims packaged process or
panel acceptance.
