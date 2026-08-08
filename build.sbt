import org.goldenport.cozy.CozyPlugin.autoImport._
import org.goldenport.cozy.CozyProjectIdentityEvidence
import sbt.Keys.*

lazy val projectIdentityEvidence = settingKey[CozyProjectIdentityEvidence]("Admitted project.yaml component identity evidence")

lazy val root = project
  .in(file("."))
  .enablePlugins(org.goldenport.cozy.CozyPlugin)
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

    libraryDependencies ++= SupervisorProjectYamlBuild.dependencies(cozyProjectMetadata.value),

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
