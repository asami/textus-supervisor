/*
 * @since   Oct. 16, 2026
 * @version Oct. 16, 2026
 */
package org.simplemodeling.textus.supervisor.impl

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import scala.util.control.NonFatal

import org.goldenport.Consequence
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.spi.supervisor.{Supervisor, SupervisorRequest, SupervisorResult}
import org.goldenport.cncf.unitofwork.UnitOfWorkOp
import org.goldenport.http.HttpResponse
import org.goldenport.protocol.Property

private[supervisor] final class ExternalTextusSupervisor(
  configuration: ExternalSupervisorConfiguration
) extends Supervisor {
  private val _route = configuration.endpoint + "/rest/v1/org.simplemodeling.textus.Supervisor/Supervisor/"
  private val _query = "textus.format=json&textus.output.shape=data&textus.command.execution-mode=sync"
  private val _headers = Map("Authorization" -> s"Bearer ${configuration.token}", "Accept" -> "application/json")

  def submit(request: SupervisorRequest)(using context: ExecutionContext): Consequence[SupervisorResult] = {
    val remaining = Duration.between(context.clock.instant(), request.deadlineAt)
    if (remaining.isNegative || remaining.isZero || remaining.compareTo(Duration.ofMillis(1)) < 0)
      Consequence.serviceUnavailable("Supervisor request deadline has expired.")
    else {
      val timeout =
        if (remaining.compareTo(Duration.ofMillis(configuration.timeoutMillis)) < 0) remaining.toMillis
        else configuration.timeoutMillis
      _execute(UnitOfWorkOp.HttpPost(
        _route + "submit?" + _query,
        Some(SupervisorOperationJson.encode(request)),
        _headers + ("Content-Type" -> "application/json"),
        _properties(timeout)
      )).flatMap(body => SupervisorOperationJson.decodeSubmit(body, request.requestId))
    }
  }

  def lookup(requestId: String)(using ExecutionContext): Consequence[Option[SupervisorResult]] =
    _execute(UnitOfWorkOp.HttpGet(
      _route + "lookup?requestId=" + URLEncoder.encode(requestId, StandardCharsets.UTF_8) + "&" + _query,
      _headers,
      _properties(configuration.timeoutMillis)
    )).flatMap(body => SupervisorOperationJson.decodeLookup(body, requestId))

  override def toString: String = "ExternalTextusSupervisor(<redacted>)"

  private def _properties(timeout: Long): Vector[Property] = Vector(
    Property("http.total-timeout-ms", timeout.toString, None),
    Property("http.max-response-bytes", "32768", None),
    Property("http.follow-redirects", "false", None)
  )

  private def _execute(operation: UnitOfWorkOp[HttpResponse])(using context: ExecutionContext): Consequence[String] = {
    val response = try {
      context.unitOfWorkInterpreter(operation).recoverWith(_ =>
        Consequence.serviceUnavailable("External Supervisor is unavailable.")
      )
    } catch {
      case NonFatal(_) => Consequence.serviceUnavailable[HttpResponse]("External Supervisor is unavailable.")
    }
    response.flatMap { value =>
      value.code match {
        case 200 => value.getString.map(Consequence.success).getOrElse(
          Consequence.operationInvalid("Invalid Supervisor operation response.")
        )
        case 401 | 403 => Consequence.securityPermissionDenied("External Supervisor denied lifecycle access.")
        case _ => Consequence.serviceUnavailable("External Supervisor is unavailable.")
      }
    }
  }
}
