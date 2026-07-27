/*
 * @version Jul. 28, 2026
 */
package org.simplemodeling.textus.supervisor.impl

import org.goldenport.Consequence
import org.goldenport.configuration.ResolvedConfiguration
import org.goldenport.cncf.config.ConfigurationAccess
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.spi.{SpiContract, SpiProvider, SpiSelection}
import org.goldenport.cncf.spi.supervisor.{Supervisor, SupervisorRequest, SupervisorResult, SupervisorState}

final class TextusSupervisorProvider(
  service: TextusSupervisorService,
  configure: () => Unit = () => ()
) extends SpiProvider[Supervisor] {
  def supports(
    contract: SpiContract[Supervisor],
    selection: SpiSelection
  )(using ExecutionContext): Boolean =
    contract.name == Supervisor.CONTRACT_NAME &&
      contract.runtimeClass == classOf[Supervisor]

  def provide(
    contract: SpiContract[Supervisor],
    selection: SpiSelection
  )(using ExecutionContext): Consequence[Supervisor] = {
    configure()
    Consequence.success(service)
  }
}

object TextusSupervisorProvider {
  final case class Runtime(
    service: TextusSupervisorService,
    provider: TextusSupervisorProvider
  )

  def apply(): TextusSupervisorProvider = {
    val service = new TextusSupervisorService(unconfigured)
    new TextusSupervisorProvider(service)
  }

  def configured(configuration: ResolvedConfiguration): TextusSupervisorProvider =
    configuredRuntime(configuration).provider

  def configuredRuntime(configuration: ResolvedConfiguration): Runtime =
    ConfigurationAccess.getString(configuration, StandaloneDevelopmentProfileRegistry.CONTROL_CENTER_HOME_KEY)
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap(value => scala.util.Try(java.nio.file.Path.of(value)).toOption)
      .map { home =>
        val supervisor = StandaloneTextusSupervisor.forHome(home)
        StandaloneDevelopmentProfileRegistry.registerConfigured(supervisor, configuration)
        val service = new TextusSupervisorService(supervisor)
        val configure = () => StandaloneDevelopmentProfileRegistry.registerConfigured(supervisor, configuration)
        Runtime(service, new TextusSupervisorProvider(service, configure))
      }
      .getOrElse {
        val service = new TextusSupervisorService(unconfigured)
        Runtime(service, new TextusSupervisorProvider(service))
      }

  val unconfigured: Supervisor = new Supervisor {
    def submit(request: SupervisorRequest)(using ExecutionContext): Consequence[SupervisorResult] =
      Consequence.success(
        SupervisorResult(
          request.requestId,
          SupervisorState.Rejected,
          Some("supervisor-driver-unconfigured"),
          Some("No Textus Supervisor deployment driver is configured."),
          "textus-supervisor",
          None,
          None,
          Some(summon[ExecutionContext].clock.instant())
        )
      )

    def lookup(requestId: String)(using ExecutionContext): Consequence[Option[SupervisorResult]] =
      Consequence.success(None)
  }
}
