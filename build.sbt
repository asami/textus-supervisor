import org.goldenport.cozy.CozyPlugin.autoImport._
import sbt.Keys.*

val scala3Version = "3.3.8"
val cncfVersion = "0.5.1"

lazy val root = project
  .in(file("."))
  .enablePlugins(org.goldenport.cozy.CozyPlugin)
  .settings(
    organization := "org.textus",
    name := "textus-supervisor",
    version := "0.1.0-SNAPSHOT",

    scalaVersion := scala3Version,
    useCoursier := false,

    resolvers += Resolver.defaultLocal,
    resolvers += Resolver.file("Local Ivy", file(Path.userHome.absolutePath + "/.ivy2/local"))(Resolver.ivyStylePatterns),
    resolvers += "Local Maven Repository" at ("file://" + Path.userHome.absolutePath + "/.m2/repository"),
    resolvers += "SimpleModeling.org" at "https://www.simplemodeling.org/repository/maven",

    libraryDependencies += "org.goldenport" %% "goldenport-cncf" % cncfVersion,
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.10" % Test,

    cozyGeneratorBackend := "cozy",
    cozyDelegateProjectDir := None,
    cozyDelegateCommand := Seq("cozy"),
    cozyManifestMetadata ++= Map(
      "component" -> "textus-supervisor",
      "boundedContext" -> "subsystem-operation",
      "domain" -> "system-administration"
    ),
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
