/*
 * @since   Oct. 17, 2026
 * @version Oct. 17, 2026
 */
package org.simplemodeling.textus.supervisor.impl

import org.goldenport.configuration.{Configuration, ConfigurationTrace, ConfigurationValue, ResolvedConfiguration}
import org.goldenport.cncf.component.{Component, ComponentCreate, ComponentOrigin}
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.http.{SupervisorHttpRouteFixture, UrlConnectionHttpDriver}
import org.goldenport.cncf.spi.{SpiContract, SpiSelection}
import org.goldenport.cncf.spi.supervisor.Supervisor
import org.goldenport.cncf.subsystem.{GenericSubsystemAuthenticationBinding, GenericSubsystemAuthenticationProviderBinding, GenericSubsystemComponentBinding, GenericSubsystemDescriptor, GenericSubsystemSecurityBinding, Subsystem}
import org.simplemodeling.textus.supervisor.TextusSupervisorComponent

/** Binds the real standalone backend; generated Operations and ingress remain production implementations. */
final class CanonicalSupervisorServiceFixture(val processFixture: CanonicalSupervisorProcessFixture) {
  val backend: StandaloneTextusSupervisor = processFixture.backend()
  val service: TextusSupervisorService = new TextusSupervisorService(backend)
  private val _provider = new TextusSupervisorProvider(service)
  private val _configuration = CanonicalSupervisorServiceFixture.configuration(
    "textus.subsystem.user-mode" -> "multi-user",
    TextusSupervisorServiceAuthenticationProvider.TOKEN_KEY -> CanonicalSupervisorServiceFixture.serviceToken,
    TextusSupervisorServiceAuthenticationProvider.PRINCIPAL_KEY -> "canonical-process-control-center")
  private val _authenticators = TextusSupervisorServiceAuthenticationProvider.fromConfiguration(_configuration)
  private val _subsystem = Subsystem(name = "canonical-supervisor-service", configuration = _configuration)
  private val _participant = new ServiceParticipantFactory().createPrimary(ComponentCreate(_subsystem, ComponentOrigin.Main))
  private val _component = new org.goldenport.cncf.component.ComponentFactory().bootstrap(_participant)
  _subsystem.add(_component)
  _component.withArtifactMetadata(Component.ArtifactMetadata(
    sourceType = "spec", name = "Supervisor", version = "0.1.1-SNAPSHOT", component = Some("Supervisor")))
  _subsystem.withDescriptor(GenericSubsystemDescriptor(
    path = processFixture.directory.resolve("http-subsystem.yaml"),
    subsystemName = "canonical-supervisor-service",
    componentBindings = Vector(GenericSubsystemComponentBinding("Supervisor")),
    security = Some(GenericSubsystemSecurityBinding(authentication = Some(GenericSubsystemAuthenticationBinding(
      convention = Some("disabled"), fallbackPrivilege = Some("none"),
      providers = Vector(GenericSubsystemAuthenticationProviderBinding(
        name = "textus-supervisor-service", component = "Supervisor", enabled = Some(true)))
    ))))
  ))

  def withEntry[A](placement: String)(run: Supervisor => A)(using ExecutionContext): A = placement match {
    case "embedded" => run(service)
    case "external" => SupervisorHttpRouteFixture.withServer(_subsystem) { endpoint =>
      val configuration = CanonicalSupervisorServiceFixture.configuration(
        ExternalSupervisorConfiguration.ENDPOINT_KEY -> endpoint,
        ExternalSupervisorConfiguration.TOKEN_KEY -> CanonicalSupervisorServiceFixture.serviceToken,
        ExternalSupervisorConfiguration.TIMEOUT_KEY -> "PT30S")
      val proxy = TextusSupervisorProvider.configuredRuntime(configuration).provider
        .provide(SpiContract(Supervisor.CONTRACT_NAME, classOf[Supervisor]), SpiSelection()).TAKE
      run(proxy)
    }
    case _ => throw new IllegalArgumentException(s"Unknown canonical Supervisor placement: $placement")
  }

  def reconstruct(): CanonicalSupervisorServiceFixture = new CanonicalSupervisorServiceFixture(processFixture)

  private final class ServiceParticipantFactory
    extends TextusSupervisorParticipantFactoryBase with Component.PrimaryComponentFactory {
    override val Supervisor: TextusSupervisorComponent.SupervisorServiceFactory =
      new DefaultSupervisorServiceFactory(() => Some(service))

    override protected def create_Component(params: ComponentCreate): Component =
      new TextusSupervisorPrimaryComponent(_provider, _authenticators)

    override protected def create_Core(params: ComponentCreate, comp: Component): Component.Core =
      component_core(TextusSupervisorComponent.name, TextusSupervisorComponent.componentId)
  }
}

object CanonicalSupervisorServiceFixture {
  def serviceToken: String = "canonical-process-service-secret"

  def administrativeContext(): ExecutionContext =
    SupervisorServiceOperationFixture.context(httpDriver = Some(new UrlConnectionHttpDriver("")))

  def unauthenticatedContext(): ExecutionContext =
    SupervisorServiceOperationFixture.context(authenticated = Some(false), privilege = None,
      httpDriver = Some(new UrlConnectionHttpDriver("")))

  def configuration(entries: (String, String)*): ResolvedConfiguration =
    ResolvedConfiguration(Configuration(entries.map { case (key, value) =>
      key -> ConfigurationValue.StringValue(value)
    }.toMap), ConfigurationTrace.empty)
}
