resolvers += "SimpleModeling.org" at "https://www.simplemodeling.org/repository/maven"
resolvers += Resolver.defaultLocal

val sbtCozyVersion = sys.props.getOrElse("sbt.cozy.version", sys.env.getOrElse("SBT_COZY_VERSION", "0.1.16"))

def localSourceDirectory(environmentName: String, sourceMarker: String): Option[File] = {
  sys.env.get(environmentName).map { raw =>
    if (raw.trim.isEmpty)
      sys.error(s"Invalid $environmentName source checkout: path must not be empty")
    val candidate = new java.io.File(raw)
    if (!candidate.isAbsolute)
      sys.error(s"Invalid $environmentName source checkout: path must be absolute")
    val source = try {
      candidate.getCanonicalFile
    } catch {
      case error: java.io.IOException =>
        sys.error(s"Invalid $environmentName source checkout: ${error.getMessage}")
    }
    val consumer = file(".").getCanonicalFile
    if (source == consumer)
      sys.error(s"Invalid $environmentName source checkout: consumer cannot depend on itself")
    val sourcepath = source.toPath
    val consumerpath = consumer.toPath
    val checkoutparent = file(".").getAbsoluteFile.toPath.normalize.resolve(".codex-workflow/checkouts")
    val candidatepath = candidate.toPath.normalize
    val projectcheckout = sourcepath.getParent == checkoutparent &&
      candidatepath.getParent == checkoutparent &&
      source.getName.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*") &&
      candidate.getName.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*")
    if (consumerpath.startsWith(sourcepath) || (sourcepath.startsWith(consumerpath) && !projectcheckout))
      sys.error(s"Invalid $environmentName source checkout: roots must be disjoint or source must be a direct .codex-workflow/checkouts child ($source, $consumer)")
    if (!(source / "build.sbt").isFile)
      sys.error(s"Invalid $environmentName source checkout: missing build.sbt in $source")
    if (!(source / sourceMarker).isFile)
      sys.error(s"Invalid $environmentName source checkout: missing $sourceMarker in $source")
    source
  }
}

lazy val sbtCozySourceProject = {
  val cncf = localSourceDirectory(
    "TEXTUS_CNCF_SOURCE_ROOT",
    "src/main/scala/org/goldenport/cncf/spi/supervisor/Supervisor.scala"
  )
  val source = localSourceDirectory(
    "TEXTUS_SBT_COZY_CNCF_SOURCE_ROOT",
    "src/main/scala/org/goldenport/cozy/CozyPlugin.scala"
  )
  val cozy = localSourceDirectory("TEXTUS_COZY_CNCF_SOURCE_ROOT", "src/main/scala/cozy/Cozy.scala")
  if (cncf.isDefined && (source.isEmpty || cozy.isEmpty))
    sys.error("TEXTUS_CNCF_SOURCE_ROOT requires both TEXTUS_SBT_COZY_CNCF_SOURCE_ROOT and TEXTUS_COZY_CNCF_SOURCE_ROOT")
  if (cozy.isDefined && (cncf.isEmpty || source.isEmpty))
    sys.error("TEXTUS_COZY_CNCF_SOURCE_ROOT requires both TEXTUS_CNCF_SOURCE_ROOT and TEXTUS_SBT_COZY_CNCF_SOURCE_ROOT")
  val selected = Vector(
    "TEXTUS_CNCF_SOURCE_ROOT" -> cncf,
    "TEXTUS_SBT_COZY_CNCF_SOURCE_ROOT" -> source,
    "TEXTUS_COZY_CNCF_SOURCE_ROOT" -> cozy
  ).flatMap { case (environmentname, directory) => directory.map(environmentname -> _) }
  selected.combinations(2).foreach { pair =>
    val (leftname, left) = pair.head
    val (rightname, right) = pair.last
    if (left.toPath.startsWith(right.toPath) || right.toPath.startsWith(left.toPath))
      sys.error(s"Invalid source checkouts: $leftname and $rightname must select disjoint producer roots ($left, $right)")
  }
  if (cncf.isDefined) {
    sys.env.get("SBT_COZY_LEGACY_DELEGATE_ROUTE").map(_.trim).filter(_.nonEmpty).foreach { route =>
      if (route != "launcher")
        sys.error(s"CNCF source mode requires SBT_COZY_LEGACY_DELEGATE_ROUTE=launcher; selected $route")
    }
  }
  source.map(directory => RootProject(directory.toURI))
}

lazy val sbtCozyPluginSettings: Seq[Def.Setting[_]] =
  if (sbtCozySourceProject.isEmpty)
    Seq(addSbtPlugin("org.goldenport" % "sbt-cozy" % sbtCozyVersion))
  else
    Seq.empty[Def.Setting[_]]

lazy val root = (project in file("."))
  .dependsOn(sbtCozySourceProject.toSeq.map(source => source % "compile->compile"): _*)
  .settings(sbtCozyPluginSettings)
