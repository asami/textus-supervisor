/*
 * @since   Jul. 28, 2026
 * @version Aug. 20, 2026
 */
package org.simplemodeling.textus.supervisor.impl

import cats.syntax.all.*
import org.goldenport.Consequence
import org.goldenport.cncf.action.ActionCall
import org.goldenport.cncf.component.{Component, ComponentCreate, ComponentId}
import org.goldenport.cncf.spi.{SpiProvider, SpiProviderComponent}
import org.goldenport.cncf.unitofwork.ExecUowM
import org.goldenport.protocol.operation.OperationResponse
import org.simplemodeling.textus.supervisor.TextusSupervisorComponent

final class ComponentFactory extends Component.BundleFactory {
  def primaryFactory: Component.PrimaryComponentFactory =
    new TextusSupervisorPrimaryFactory

  override def componentletFactories: Vector[Component.ComponentletFactory] =
    Vector.empty
}

abstract class TextusSupervisorParticipantFactoryBase extends TextusSupervisorComponent.Factory {
  protected final val shared_services =
    Vector(TextusSupervisorComponent.SupervisorService)

  private var _runtime: Option[TextusSupervisorProvider.Runtime] = None

  override val Supervisor: TextusSupervisorComponent.SupervisorServiceFactory =
    new DefaultSupervisorServiceFactory(() => _runtime.map(_.service))

  protected final def configure_runtime(params: ComponentCreate): TextusSupervisorProvider.Runtime = {
    val runtime = TextusSupervisorProvider.configuredRuntime(params.configuration)
    _runtime = Some(runtime)
    runtime
  }

  protected final def component_core(
    name: String,
    componentId: ComponentId
  ): Component.Core =
    spec_create(name, componentId, shared_services)
}

final class TextusSupervisorPrimaryComponent(
  provider: TextusSupervisorProvider
) extends TextusSupervisorComponent with SpiProviderComponent {
  def spiProviders: Vector[SpiProvider[?]] =
    Vector(provider)
}

final class TextusSupervisorPrimaryFactory extends TextusSupervisorParticipantFactoryBase with Component.PrimaryComponentFactory {
  override protected def create_Component(params: ComponentCreate): Component = {
    val provider = configure_runtime(params).provider
    new TextusSupervisorPrimaryComponent(provider)
  }

  override protected def create_Core(
    params: ComponentCreate,
    comp: Component
  ): Component.Core =
    component_core(TextusSupervisorComponent.name, TextusSupervisorComponent.componentId)
}

final class DefaultSupervisorServiceFactory(
  service: () => Option[TextusSupervisorService]
) extends TextusSupervisorComponent.SupervisorServiceFactory {
  import TextusSupervisorComponent.SupervisorService.*

  override def createSubmitActionCall(
    core: ActionCall.Core,
    action: SupervisorRequest
  ): SubmitActionCall =
    SubmitActionCallImpl(core, action)

  override def createLookupActionCall(
    core: ActionCall.Core,
    action: SupervisorLookup
  ): LookupActionCall =
    LookupActionCallImpl(core, action)

  private final case class SubmitActionCallImpl(
    core: ActionCall.Core,
    override val action: SupervisorRequest
  ) extends SubmitActionCall {
    protected def build_Program: ExecUowM[OperationResponse] =
      exec_from(_submit(core, action.record)).map(value => OperationResponse(value.toRecord()))
  }

  private final case class LookupActionCallImpl(
    core: ActionCall.Core,
    override val action: SupervisorLookup
  ) extends LookupActionCall {
    protected def build_Program: ExecUowM[OperationResponse] =
      exec_from(_lookup(core, action.record)).map(value => OperationResponse(value.toRecord()))
  }

  private def _submit(
    core: ActionCall.Core,
    record: org.goldenport.record.Record
  ): Consequence[org.simplemodeling.textus.supervisor.value.SupervisorResult] =
    for {
      implementation <- _service
      value <- org.simplemodeling.textus.supervisor.value.SupervisorRequest.createC(record)
      request <- TextusSupervisorService.request(value)
      result <- implementation.submit(request)(using core.executionContext)
    } yield TextusSupervisorService.result(result)

  private def _lookup(
    core: ActionCall.Core,
    record: org.goldenport.record.Record
  ): Consequence[org.simplemodeling.textus.supervisor.value.SupervisorLookupResult] =
    for {
      implementation <- _service
      value <- org.simplemodeling.textus.supervisor.value.SupervisorLookup.createC(record)
      result <- implementation.lookup(value.requestId.value)(using core.executionContext)
    } yield org.simplemodeling.textus.supervisor.value.SupervisorLookupResult(
      result.map(TextusSupervisorService.result)
    )

  private def _service: Consequence[TextusSupervisorService] =
    service()
      .map(Consequence.success)
      .getOrElse(Consequence.serviceUnavailable("Textus Supervisor Service is not configured"))
}
