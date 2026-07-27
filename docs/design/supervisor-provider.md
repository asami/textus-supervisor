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
