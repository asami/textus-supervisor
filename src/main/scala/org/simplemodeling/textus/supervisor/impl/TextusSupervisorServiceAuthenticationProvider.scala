/*
 * @since   Oct. 16, 2026
 * @version Oct. 16, 2026
 */
package org.simplemodeling.textus.supervisor.impl

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import org.goldenport.Consequence
import org.goldenport.configuration.ResolvedConfiguration
import org.goldenport.cncf.context.{Capability, ExecutionContext, PrincipalId, SecurityContext, SecurityLevel, SubjectKind}
import org.goldenport.cncf.security.{AuthenticationProvider, AuthenticationRequest, AuthenticationResult, PublicPrincipalId}

private[supervisor] final class TextusSupervisorServiceAuthenticationProvider private (
  private val _token: String,
  private val _principal_id: String
) extends AuthenticationProvider {
  def name: String = "textus-supervisor-service"

  def authenticate(request: AuthenticationRequest)(using ExecutionContext): Consequence[Option[AuthenticationResult]] = {
    val accepted = request.accessToken.flatMap(ExternalSupervisorConfiguration.validToken).exists(token =>
      MessageDigest.isEqual(_token.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))
    )
    Consequence.success(Option.when(accepted)(AuthenticationResult(
      principalId = PrincipalId(_principal_id),
      capabilities = Set(Capability(SecurityContext.Privilege.Operator.name)),
      level = SecurityLevel("service"),
      subjectKind = SubjectKind.Service
    )))
  }

  override def toString: String = "TextusSupervisorServiceAuthenticationProvider(<redacted>)"
}

private[supervisor] object TextusSupervisorServiceAuthenticationProvider {
  val TOKEN_KEY = "textus-supervisor.service.authentication.token"
  val PRINCIPAL_KEY = "textus-supervisor.service.authentication.principal-id"

  def fromConfiguration(configuration: ResolvedConfiguration): Vector[AuthenticationProvider] = {
    val principal = ExternalSupervisorConfiguration.setting(configuration, PRINCIPAL_KEY) match {
      case None => Some("textus-supervisor-control-center")
      case Some(value) => value.flatMap(PublicPrincipalId.option).map(_.value)
        .filter(value => !value.exists(Character.isISOControl))
    }
    (for {
      token <- ExternalSupervisorConfiguration.setting(configuration, TOKEN_KEY).flatten
        .flatMap(ExternalSupervisorConfiguration.validToken)
      identity <- principal
    } yield new TextusSupervisorServiceAuthenticationProvider(token, identity)).toVector
  }
}
