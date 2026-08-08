import org.goldenport.cozy.{CozyProjectConfig, CozyProjectIdentityContract, CozyProjectIdentityEvidence}
import sbt.*

/*
 * @since   Aug.  8, 2026
 * @version Aug.  8, 2026
 * @author  ASAMI, Tomoharu
 */
object SupervisorProjectYamlBuild {
  def requiredValue(config: CozyProjectConfig, path: String): String =
    config.value(path).getOrElse(sys.error(s"$path is required in project.yaml"))

  def admitted(config: CozyProjectConfig, scalaBinaryVersion: String): CozyProjectIdentityEvidence =
    CozyProjectIdentityContract.requireAdmitted(config, scalaBinaryVersion)

  def organization(evidence: CozyProjectIdentityEvidence): String =
    evidence.organization.getOrElse(sys.error("project.namespace must project an organization"))

  def moduleName(evidence: CozyProjectIdentityEvidence): String =
    evidence.moduleName.getOrElse(sys.error("project identity must project a module name"))

  def version(evidence: CozyProjectIdentityEvidence): String = evidence.effectiveVersion

  def carBaseName(evidence: CozyProjectIdentityEvidence): String =
    evidence.carBaseName.getOrElse(sys.error("project identity must project a CAR base name"))

  def manifestMetadata(evidence: CozyProjectIdentityEvidence): Map[String, String] = evidence.manifestMetadata

  def dependencies(config: CozyProjectConfig): Seq[ModuleID] =
    _dependencies(config, "compile", None) ++ _dependencies(config, "test", Some(Test))

  private def _dependencies(
    config: CozyProjectConfig,
    scope: String,
    configuration: Option[Configuration]
  ): Seq[ModuleID] =
    config.list(s"build.dependencies.$scope").map { coordinate =>
      val module = _module(coordinate)
      configuration.fold(module)(module % _)
    }

  private def _module(coordinate: String): ModuleID =
    coordinate.split(":", -1).toList match {
      case organization :: "" :: artifact :: version :: Nil =>
        organization %% artifact % version
      case organization :: artifact :: version :: Nil =>
        organization % artifact % version
      case _ =>
        sys.error(
          s"Invalid project.yaml dependency '$coordinate'; expected organization:artifact:version or organization::artifact:version"
        )
    }
}
