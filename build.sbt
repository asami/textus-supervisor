import org.goldenport.cozy.CozyPlugin.autoImport._
import org.goldenport.cozy.CozyProjectIdentityEvidence
import org.goldenport.cozy.CozyProjectConfig
import sbt.Keys.*

lazy val projectIdentityEvidence = settingKey[CozyProjectIdentityEvidence]("Admitted project.yaml component identity evidence")
lazy val CanonicalProcess = config("canonicalProcess") extend Test

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

def localSourceProject(environmentName: String, sourceMarker: String): Option[RootProject] =
  localSourceDirectory(environmentName, sourceMarker).map(source => RootProject(source.toURI))

lazy val cncfSourceProject = localSourceProject(
  "TEXTUS_CNCF_SOURCE_ROOT",
  "src/main/scala/org/goldenport/cncf/spi/supervisor/Supervisor.scala"
)

lazy val cozySourceDirectory = localSourceDirectory(
  "TEXTUS_COZY_CNCF_SOURCE_ROOT",
  "src/main/scala/cozy/Cozy.scala"
)

def sourceProjectMetadata(config: CozyProjectConfig, cncfVersion: String, cozyVersion: String): CozyProjectConfig = {
  val compilecoordinates = config.list("build.dependencies.compile")
  val matches = compilecoordinates.filter { coordinate =>
    coordinate.split(":", -1).toList match {
      case "org.goldenport" :: "" :: "goldenport-cncf" :: _ :: Nil => true
      case "org.goldenport" :: "goldenport-cncf" :: _ :: Nil => true
      case _ => false
    }
  }
  if (matches.size != 1)
    sys.error(s"CNCF source metadata requires exactly one org.goldenport goldenport-cncf compile coordinate; found ${matches.size}")
  val selected = matches.head
  val revised = compilecoordinates.map { coordinate =>
    if (coordinate == selected)
      coordinate.substring(0, coordinate.lastIndexOf(':') + 1) + cncfVersion
    else
      coordinate
  }
  config.copy(
    values = config.values.updated("build.cozyVersion", cozyVersion),
    lists = config.lists.updated("build.dependencies.compile", revised)
  )
}

val cncfSourceCozyVersion = "0.3.3-SNAPSHOT"

lazy val cncfSourceSettings: Seq[Def.Setting[_]] = cncfSourceProject.toSeq.flatMap { source =>
  Seq(
    cozyProjectMetadata := sourceProjectMetadata(
      CozyProjectConfig.load(cozyProjectFile.value),
      (source / version).value,
      cncfSourceCozyVersion
    ),
    cozyDelegateProjectDir := cozySourceDirectory,
    cozyDelegateCommand := Seq("cozy", "--runtime", cncfSourceCozyVersion),
    TaskKey[Option[File]]("cozyCncfRuntimeDescriptorInput") :=
      Some((source / TaskKey[File]("generateCncfRuntimeDescriptor")).value),
    TaskKey[Option[File]]("cozyCncfRuntimeArtifactInput") :=
      Some((source / Compile / packageBin).value)
  )
}

lazy val root = project
  .in(file("."))
  .configs(CanonicalProcess)
  .settings(inConfig(CanonicalProcess)(Defaults.testSettings))
  .enablePlugins(org.goldenport.cozy.CozyPlugin)
  .dependsOn(cncfSourceProject.toSeq.map(source => source % "compile->compile"): _*)
  .settings(
    projectIdentityEvidence := SupervisorProjectYamlBuild.admitted(cozyProjectMetadata.value, scalaBinaryVersion.value),
    organization := SupervisorProjectYamlBuild.organization(projectIdentityEvidence.value),
    moduleName := SupervisorProjectYamlBuild.moduleName(projectIdentityEvidence.value),
    name := moduleName.value,
    version := SupervisorProjectYamlBuild.version(projectIdentityEvidence.value),

    scalaVersion := SupervisorProjectYamlBuild.requiredValue(cozyProjectMetadata.value, "build.scalaVersion"),
    useCoursier := false,

    resolvers += Resolver.defaultLocal,
    resolvers += Resolver.file("Local Ivy", file(Path.userHome.absolutePath + "/.ivy2/local"))(Resolver.ivyStylePatterns),
    resolvers += "Local Maven Repository" at ("file://" + Path.userHome.absolutePath + "/.m2/repository"),
    resolvers += "SimpleModeling.org" at "https://www.simplemodeling.org/repository/maven",

    libraryDependencies ++= SupervisorProjectYamlBuild.dependencies(cozyProjectMetadata.value)
      .filterNot(module => cncfSourceProject.isDefined &&
        module.organization == "org.goldenport" && module.name == "goldenport-cncf" &&
        module.configurations.isEmpty),

    cozyGeneratorBackend := "cozy",
    cozyDelegateProjectDir := None,
    cozyDelegateCommand := Seq("cozy"),
    cozyCarName := SupervisorProjectYamlBuild.carBaseName(projectIdentityEvidence.value),
    cozyManifestMetadata ++=
      cozyProjectMetadata.value.mapUnder("packaging.car.manifest_metadata") ++
        SupervisorProjectYamlBuild.manifestMetadata(projectIdentityEvidence.value),
    publish := {
      val _ = cozyPublishCar.value
      ()
    },
    publishLocal := {
      val _ = cozyPublishLocalCar.value
      ()
    },

    Compile / sourceGenerators += Def.task {
      val out = (Compile / sourceManaged).value / "org" / "simplemodeling" / "textus" / "supervisor" / "meta" / "BuildVersion.scala"
      val content =
        "package org.simplemodeling.textus.supervisor.meta\n\nobject BuildVersion {\n" +
          "  val name: String = \"" + name.value + "\"\n" +
          "  val version: String = \"" + version.value + "\"\n" +
          "  val scalaVersion: String = \"" + scalaVersion.value + "\"\n" +
          "}\n"
      IO.createDirectory(out.getParentFile)
      IO.write(out, content)
      Seq(out)
    }.taskValue
  )
  .settings(cncfSourceSettings)
  .settings(
    CanonicalProcess / dependencyClasspath := (Test / fullClasspath).value,
    CanonicalProcess / fork := true,
    CanonicalProcess / parallelExecution := false,
    CanonicalProcess / envVars += "PATH" -> (
      (target.value / "canonical-process" / "bin").getAbsolutePath +
        java.io.File.pathSeparator + sys.env.getOrElse("PATH", "")
    )
  )
