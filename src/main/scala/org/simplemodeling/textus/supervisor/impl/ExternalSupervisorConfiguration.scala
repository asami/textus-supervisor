/*
 * @since   Oct. 16, 2026
 * @version Oct. 16, 2026
 */
package org.simplemodeling.textus.supervisor.impl

import java.net.URI
import java.time.Duration
import scala.util.Try

import org.goldenport.Consequence
import org.goldenport.configuration.{ConfigurationValue, ResolvedConfiguration}
import org.goldenport.cncf.config.ConfigurationAccess

private[supervisor] final class ExternalSupervisorConfiguration private (
  val endpoint: String,
  val token: String,
  val timeoutMillis: Long
) {
  override def toString: String = "ExternalSupervisorConfiguration(<redacted>)"
}

private[supervisor] object ExternalSupervisorConfiguration {
  val ENDPOINT_KEY = "textus-supervisor.external.endpoint"
  val TOKEN_KEY = "textus-supervisor.external.authentication.token"
  val TIMEOUT_KEY = "textus-supervisor.external.timeout"

  def selected(configuration: ResolvedConfiguration): Boolean =
    Vector(ENDPOINT_KEY, TOKEN_KEY, TIMEOUT_KEY).exists(key => setting(configuration, key).nonEmpty)

  def fromConfiguration(configuration: ResolvedConfiguration): Consequence[ExternalSupervisorConfiguration] = {
    val parsed = for {
      endpoint <- setting(configuration, ENDPOINT_KEY).flatten.flatMap(_endpoint)
      token <- setting(configuration, TOKEN_KEY).flatten.flatMap(validToken)
      timeout <- setting(configuration, TIMEOUT_KEY) match {
        case None => Some(5000L)
        case Some(value) => value.flatMap(_timeout)
      }
    } yield new ExternalSupervisorConfiguration(endpoint, token, timeout)
    parsed.map(Consequence.success).getOrElse(
      Consequence.serviceUnavailable("External Supervisor configuration is unavailable.")
    )
  }

  // Retain explicit invalid composite/empty values instead of letting the
  // string accessor erase their presence and accidentally select local mode.
  def setting(configuration: ResolvedConfiguration, key: String): Option[Option[String]] = {
    val values = configuration.configuration.values
    val raw = values.get(key).orElse(_lookup(values, key.split('.').toList))
    raw match {
      case Some(_: ConfigurationValue.ObjectValue) | Some(_: ConfigurationValue.ListValue) |
          Some(ConfigurationValue.NullValue) => Some(None)
      case Some(_) => Some(ConfigurationAccess.getString(configuration, key))
      case None =>
        if (Option(System.getProperty(key)).exists(_.trim.isEmpty)) Some(None)
        else ConfigurationAccess.getString(configuration, key).map(value => Some(value))
          .orElse(Option(System.getProperty(key)).map(_ => None))
    }
  }

  def validToken(value: String): Option[String] =
    Option(value).map(_.trim).filter(value =>
      value.nonEmpty && !value.exists(character => Character.isWhitespace(character) || Character.isISOControl(character))
    )

  private def _lookup(values: Map[String, ConfigurationValue], segments: List[String]): Option[ConfigurationValue] =
    segments match {
      case head :: Nil => values.get(head)
      case head :: tail => values.get(head) match {
        case Some(ConfigurationValue.ObjectValue(nested)) => _lookup(nested, tail)
        case _ => None
      }
      case Nil => None
    }

  private def _endpoint(value: String): Option[String] =
    Try(new URI(value.trim)).toOption.filter { uri =>
      val scheme = Option(uri.getScheme).map(_.toLowerCase(java.util.Locale.ROOT))
      val host = Option(uri.getHost).filter(_.nonEmpty)
      uri.isAbsolute && host.nonEmpty &&
        (scheme.contains("https") || (scheme.contains("http") && host.exists(value =>
          value == "127.0.0.1" || value == "[::1]" || value == "::1"
        ))) &&
        Option(uri.getRawPath).forall(path => path.isEmpty || path == "/") &&
        uri.getRawUserInfo == null && uri.getRawQuery == null && uri.getRawFragment == null &&
        (uri.getPort == -1 || (uri.getPort >= 1 && uri.getPort <= 65535))
    }.map { uri =>
      val origin = uri.toString
      (uri.getScheme.toLowerCase(java.util.Locale.ROOT) + origin.substring(origin.indexOf(':'))).stripSuffix("/")
    }

  private def _timeout(value: String): Option[Long] =
    Try(Duration.parse(value.trim)).toOption.filter(duration =>
      !duration.isNegative && !duration.isZero && duration.getNano % 1000000 == 0 &&
        duration.compareTo(Duration.ofSeconds(30)) <= 0
    ).flatMap(duration => Try(duration.toMillis).toOption).filter(_ >= 1)
}
