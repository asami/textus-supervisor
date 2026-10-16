/*
 * @since   Oct. 16, 2026
 * @version Oct. 16, 2026
 */
package org.simplemodeling.textus.supervisor.impl

import java.time.Instant
import scala.util.Try

import io.circe.{Json, JsonObject}
import io.circe.parser.parse
import org.goldenport.Consequence
import org.goldenport.cncf.context.RuntimeContext
import org.goldenport.cncf.spi.supervisor.{SupervisorRequest, SupervisorResult, SupervisorState}

private[supervisor] object SupervisorOperationJson {
  def encode(request: SupervisorRequest): String = Json.obj(
    "requestId" -> Json.fromString(request.requestId),
    "idempotencyKey" -> Json.fromString(request.idempotencyKey),
    "targetId" -> Json.fromString(request.targetId),
    "deploymentId" -> request.deploymentId.map(Json.fromString).getOrElse(Json.Null),
    "action" -> Json.fromString(request.action.toString.toLowerCase(java.util.Locale.ROOT)),
    "operatorSubjectId" -> Json.fromString(request.operatorSubjectId),
    "deadlineAt" -> Json.fromString(request.deadlineAt.toString)
  ).noSpaces

  def decodeSubmit(body: String, requestId: String): Consequence[SupervisorResult] =
    _consequence(_object(body).flatMap(value => _result(value, requestId)))

  def decodeLookup(body: String, requestId: String): Consequence[Option[SupervisorResult]] =
    _consequence(_object(body).flatMap { wrapper =>
      wrapper("result") match {
        case None => Right(None)
        case Some(value) if value.isNull => Right(None)
        case Some(value) => value.asObject.toRight(()).flatMap(_result(_, requestId)).map(Some(_))
      }
    })

  private def _object(body: String): Either[Unit, JsonObject] =
    parse(body).left.map(_ => ()).flatMap(_.asObject.toRight(()))

  private def _result(value: JsonObject, requestid: String): Either[Unit, SupervisorResult] =
    for {
      identity <- _required(value, "requestId", 512).filterOrElse(_ == requestid, ())
      statename <- _required(value, "state", 128)
      state <- SupervisorState.values.find(_.toString == statename).toRight(())
      code <- _optional(value, "diagnosticCode", 1, 128)
      diagnostic <- _optional(value, "diagnostic", 0, 4096)
      supervisor <- _required(value, "supervisorId", 512)
      instance <- _optional(value, "instanceId", 1, 512)
      accepted <- _instant(value, "acceptedAt")
      completed <- _instant(value, "completedAt")
    } yield SupervisorResult(identity, state, code, diagnostic, supervisor, instance, accepted, completed)

  private def _field(value: JsonObject, name: String): Option[Json] =
    value(RuntimeContext.PropertyNameContext.default.preferredName(value.keys.toSet, name))

  private def _required(value: JsonObject, name: String, maximum: Int): Either[Unit, String] =
    _field(value, name).flatMap(_.asString).filter(text => text.nonEmpty && text.length <= maximum).toRight(())

  private def _optional(value: JsonObject, name: String, minimum: Int, maximum: Int): Either[Unit, Option[String]] =
    _field(value, name) match {
      case None => Right(None)
      case Some(json) if json.isNull => Right(None)
      case Some(json) => json.asString.filter(text => text.length >= minimum && text.length <= maximum)
        .toRight(()).map(Some(_))
    }

  private def _instant(value: JsonObject, name: String): Either[Unit, Option[Instant]] =
    _optional(value, name, 1, 128).flatMap {
      case None => Right(None)
      case Some(text) => Try(Instant.parse(text)).toOption.toRight(()).map(Some(_))
    }

  private def _consequence[A](value: Either[Unit, A]): Consequence[A] = value match {
    case Right(result) => Consequence.success(result)
    case Left(_) => Consequence.operationInvalid("Invalid Supervisor operation response.")
  }
}
