/*
 * @since   Jul. 28, 2026
 * @version Aug. 20, 2026
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
import org.simplemodeling.textus.supervisor.datatype.{
  SupervisorDiagnostic as SupervisorDiagnosticValue,
  SupervisorDiagnosticCode as SupervisorDiagnosticCodeValue,
  SupervisorId as SupervisorIdValue,
  SupervisorInstanceId as SupervisorInstanceIdValue,
  SupervisorRequestId as SupervisorRequestIdValue,
  SupervisorRequestState as SupervisorRequestStateValue
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
    _action(value.action.value).map { action =>
      SupervisorRequest(
        value.requestId.value,
        value.idempotencyKey.value,
        value.targetId.value,
        value.deploymentId.map(_.value),
        action,
        value.operatorSubjectId.value,
        value.deadlineAt
      )
    }

  def result(
    value: SupervisorResult
  ): org.simplemodeling.textus.supervisor.value.SupervisorResult =
    org.simplemodeling.textus.supervisor.value.SupervisorResult(
      SupervisorRequestIdValue(value.requestId),
      SupervisorRequestStateValue(value.state.toString),
      value.diagnosticCode.map(SupervisorDiagnosticCodeValue.apply),
      value.diagnostic.map(SupervisorDiagnosticValue.apply),
      SupervisorIdValue(value.supervisorId),
      value.instanceId.map(SupervisorInstanceIdValue.apply),
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
