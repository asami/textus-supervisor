/*
 * @since   Jul. 28, 2026
 * @version Jul. 28, 2026
 * @author  ASAMI, Tomoharu
 */
package org.simplemodeling.textus.supervisor.impl

import org.goldenport.Consequence
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.spi.supervisor.{
  Supervisor,
  SupervisorAction,
  SupervisorRequest,
  SupervisorResult
}

/**
 * Application Service shared by generated component Operations and the CNCF
 * Supervisor SPI adapter.
 */
final class TextusSupervisorService(
  lifecycleSupervisor: Supervisor
) extends Supervisor {
  def submit(request: SupervisorRequest)(using ExecutionContext): Consequence[SupervisorResult] =
    lifecycleSupervisor.submit(request)

  def lookup(requestId: String)(using ExecutionContext): Consequence[Option[SupervisorResult]] =
    lifecycleSupervisor.lookup(requestId)
}

object TextusSupervisorService {
  def request(
    value: org.simplemodeling.textus.supervisor.value.SupervisorRequest
  ): Consequence[SupervisorRequest] =
    _action(value.action).map { action =>
      SupervisorRequest(
        value.requestId,
        value.idempotencyKey,
        value.targetId,
        value.deploymentId,
        action,
        value.operatorSubjectId,
        value.deadlineAt
      )
    }

  def result(
    value: SupervisorResult
  ): org.simplemodeling.textus.supervisor.value.SupervisorResult =
    org.simplemodeling.textus.supervisor.value.SupervisorResult(
      value.requestId,
      value.state.toString,
      value.diagnosticCode,
      value.diagnostic,
      value.supervisorId,
      value.instanceId,
      value.acceptedAt,
      value.completedAt
    )

  private def _action(value: String): Consequence[SupervisorAction] =
    value.trim.toLowerCase match {
      case "start" => Consequence.success(SupervisorAction.Start)
      case "stop" => Consequence.success(SupervisorAction.Stop)
      case "restart" => Consequence.success(SupervisorAction.Restart)
      case _ => Consequence.valueInvalid(value, org.goldenport.schema.XString)
    }
}
