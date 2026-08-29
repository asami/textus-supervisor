/*
 * @since   Jul. 28, 2026
 * @version Aug. 28, 2026
 * @author  ASAMI, Tomoharu
 */
package org.simplemodeling.textus.supervisor.impl

import java.io.ByteArrayOutputStream
import java.net.{InetAddress, InetSocketAddress, ServerSocket}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.Try

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*
import io.circe.parser.decode
import io.circe.syntax.*
import org.goldenport.Consequence
import org.goldenport.configuration.ResolvedConfiguration
import org.goldenport.cncf.config.ConfigurationAccess
import org.goldenport.cncf.component.identity.{ComponentId, ComponentIdentityProjection}
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.spi.supervisor.{Supervisor, SupervisorAction, SupervisorRequest, SupervisorResult, SupervisorState}
import org.yaml.snakeyaml.Yaml

sealed trait StandaloneLaunchProfile {
  def targetId: String
  def deploymentId: String
  def workingDirectory: Option[Path]
  def defaultPort: Option[Int]
  def forTarget(targetId: String): StandaloneLaunchProfile
}

final case class StandaloneDevelopmentLaunchProfile(
  targetId: String,
  developmentDirectory: Path,
  developmentDefaultPort: Int,
  deploymentId: String = StandaloneDevelopmentLaunchProfile.STANDALONE_DEPLOYMENT_ID,
  developmentRepositoryDirectories: Vector[Path] = Vector.empty
) extends StandaloneLaunchProfile {
  val workingDirectory: Option[Path] = Some(developmentDirectory)
  val defaultPort: Option[Int] = Some(developmentDefaultPort)
  def forTarget(targetId: String): StandaloneLaunchProfile = this
}

object StandaloneDevelopmentLaunchProfile {
  val PROFILE_UNAVAILABLE = "supervisor-launch-profile-unavailable"
  val STANDALONE_DEPLOYMENT_ID = "standalone"

  def discover(developmentDirectory: Path): Option[StandaloneDevelopmentLaunchProfile] = {
    val directory = developmentDirectory.toAbsolutePath.normalize
    val descriptor = directory.resolve("project.yaml")
    Try(Files.readString(descriptor, StandardCharsets.UTF_8)).toOption // cncf-car-lint: ignore documented standalone provider/bootstrap host boundary reads its private descriptor.
      .flatMap(_profile(_, directory))
  }

  def resolve(targetId: String, developmentDirectory: Path): Either[String, StandaloneDevelopmentLaunchProfile] = {
    val directory = developmentDirectory.toAbsolutePath.normalize
    val descriptor = directory.resolve("project.yaml")
    if (!Files.isDirectory(directory) || !Files.isRegularFile(descriptor))
      Left(PROFILE_UNAVAILABLE)
    else
      Try(Files.readString(descriptor, StandardCharsets.UTF_8)).toEither.left.map(_ => PROFILE_UNAVAILABLE).flatMap { document => // cncf-car-lint: ignore documented standalone provider/bootstrap host boundary reads its private descriptor.
        _profile(document, directory)
          .filter(_.targetId == targetId)
          .toRight(PROFILE_UNAVAILABLE)
      }
  }

  private def _profile(document: String, directory: Path): Option[StandaloneDevelopmentLaunchProfile] =
    for {
      root <- _yaml_mapping(document)
      projectmetadata <- root.get("project").map(_mapping)
      kind <- _string(projectmetadata, "kind") if kind == "car"
      namespace <- _string(projectmetadata, "namespace")
      id <- _string(projectmetadata, "id")
      projection <- Try(ComponentIdentityProjection.of(ComponentId.require(s"$namespace.$id"))).toOption
      componentmetadata <- projectmetadata.get("component").map(_mapping)
      configuration <- componentmetadata.get("config").map(_mapping)
      porttext <- _string(configuration, "textus.server.default-port")
      port <- Try(porttext.toInt).toOption.filter(value => 1 <= value && value <= 65535)
    } yield StandaloneDevelopmentLaunchProfile(projection.mavenArtifactId(), directory, port)

  private def _yaml_mapping(document: String): Option[Map[String, Any]] =
    Try(new Yaml().load[AnyRef](document)).toOption.collect {
      case values: java.util.Map[?, ?] => _mapping(values)
    }

  private def _mapping(value: Any): Map[String, Any] = value match {
    case values: java.util.Map[?, ?] =>
      values.asScala.collect { case (key: String, item) => key -> item }.toMap
    case _ => Map.empty
  }

  private def _string(values: Map[String, Any], key: String): Option[String] =
    values.get(key).collect { case value: String if value.trim.nonEmpty => value.trim }
}

final case class StandaloneRepositoryLaunchProfile(
  deploymentId: String,
  repositoryRoot: String,
  targetId: String = ""
) extends StandaloneLaunchProfile {
  val workingDirectory: Option[Path] = None
  val defaultPort: Option[Int] = None
  def forTarget(targetId: String): StandaloneLaunchProfile = copy(targetId = targetId)
}

object StandaloneDevelopmentProfileRegistry {
  val CONTROL_CENTER_HOME_KEY = "textus-control-center.home"
  val DEVELOPMENT_ROOT_KEY = "textus-control-center.catalog.development.root"

  def registerConfigured(
    supervisor: StandaloneTextusSupervisor,
    configuration: ResolvedConfiguration
  ): Unit = {
    ConfigurationAccess.getString(configuration, DEVELOPMENT_ROOT_KEY).toVector
      .map(path => DevelopmentCatalogSource(StandaloneDevelopmentLaunchProfile.STANDALONE_DEPLOYMENT_ID, path))
      .foreach(_register_development_profiles(supervisor, _))
    ConfigurationAccess.getString(configuration, CONTROL_CENTER_HOME_KEY).toVector
      .flatMap(home => _catalog_sources(Path.of(home).resolve("catalog.yaml")))
      .foreach {
        case source: DevelopmentCatalogSource => _register_development_profiles(supervisor, source)
        case LocalRepositoryCatalogSource(sourceid, catalogroot) =>
          _local_repository_root(catalogroot).foreach(root =>
            supervisor.registerRepositoryProfile(StandaloneRepositoryLaunchProfile(s"LOCAL:$sourceid", root))
          )
        case PublicRepositoryCatalogSource(sourceid, catalogbaseurl) =>
          _public_repository_root(catalogbaseurl).foreach(root =>
            supervisor.registerRepositoryProfile(StandaloneRepositoryLaunchProfile(s"PUBLIC:$sourceid", root))
          )
      }
  }

  private sealed trait CatalogSource
  private final case class DevelopmentCatalogSource(sourceid: String, path: String) extends CatalogSource
  private final case class LocalRepositoryCatalogSource(sourceid: String, catalogroot: String) extends CatalogSource
  private final case class PublicRepositoryCatalogSource(sourceid: String, catalogbaseurl: String) extends CatalogSource

  private def _register_development_profiles(
    supervisor: StandaloneTextusSupervisor,
    source: DevelopmentCatalogSource
  ): Unit = {
    val root = Path.of(source.path).toAbsolutePath.normalize
    if (Files.isDirectory(root)) {
      val children = Files.list(root)
      try {
        val developmentdirectories = children.iterator().asScala
          .filter(path => path.getFileName.toString.startsWith("textus-"))
          .map(_.toAbsolutePath.normalize)
          .toVector
          .sortBy(_.toString)
        val profiles = developmentdirectories.flatMap(StandaloneDevelopmentLaunchProfile.discover)
        profiles
          .map(profile => profile.copy(
            deploymentId = _development_deployment_id(source.sourceid),
            developmentRepositoryDirectories = Vector(profile.developmentDirectory)
          ))
          .foreach(supervisor.registerDevelopmentProfile)
      }
      finally children.close()
    }
  }

  private def _catalog_sources(path: Path): Vector[CatalogSource] =
    Try(Files.readString(path, StandardCharsets.UTF_8)).toOption.toVector.flatMap { document => // cncf-car-lint: ignore documented standalone provider/bootstrap host boundary reads its private catalog.
      val lines = document.linesIterator.toVector
      _list_mappings(lines, "development", "roots").flatMap { values =>
        for {
          sourceid <- values.get("id").filter(_.nonEmpty)
          sourcepath <- values.get("path").filter(_.nonEmpty)
        } yield DevelopmentCatalogSource(sourceid, sourcepath)
      } ++
        _single_mapping(lines, "local-repository").flatMap { values =>
          for {
            sourceid <- values.get("id").filter(_.nonEmpty)
            catalogroot <- values.get("catalog-root").filter(_.nonEmpty)
          } yield LocalRepositoryCatalogSource(sourceid, catalogroot)
        }.toVector ++
        _list_mappings(lines, "public-repositories").flatMap { values =>
          for {
            sourceid <- values.get("id").filter(_.nonEmpty)
            catalogbaseurl <- values.get("catalog-base-url").filter(_.nonEmpty)
          } yield PublicRepositoryCatalogSource(sourceid, catalogbaseurl)
        }
    }

  private def _list_mappings(
    lines: Vector[String],
    section: String,
    subsection: String = ""
  ): Vector[Map[String, String]] = {
    val sectionlines = _section_lines(lines, section, 0)
    val entries = if (subsection.isEmpty) sectionlines else _section_lines(sectionlines, subsection, 2)
    _list_entries(entries, if (subsection.isEmpty) 2 else 4)
  }

  private def _single_mapping(lines: Vector[String], section: String): Option[Map[String, String]] =
    Option(_mapping(_section_lines(lines, section, 0), 2)).filter(_.nonEmpty)

  private def _section_lines(lines: Vector[String], section: String, indentation: Int): Vector[String] =
    lines.indexWhere(line => _indentation(line) == indentation && line.trim == s"$section:") match {
      case index if index >= 0 =>
        lines.drop(index + 1).takeWhile(line => line.trim.isEmpty || _indentation(line) > indentation)
      case _ => Vector.empty
    }

  private def _list_entries(lines: Vector[String], indentation: Int): Vector[Map[String, String]] = {
    val indexes = lines.zipWithIndex.collect {
      case (line, index) if _indentation(line) == indentation && line.trim.startsWith("-") => index
    }
    indexes.zip(indexes.drop(1) :+ lines.size).map { case (start, end) =>
      _mapping(lines.slice(start, end), indentation)
    }
  }

  private def _mapping(lines: Vector[String], indentation: Int): Map[String, String] =
    lines.flatMap { line =>
      val trimmed = line.trim.stripPrefix("-").trim
      Option.when(_indentation(line) >= indentation && trimmed.contains(":")) {
        val index = trimmed.indexOf(':')
        trimmed.take(index) -> _value(trimmed.drop(index + 1))
      }
    }.toMap

  private def _indentation(line: String): Int = line.takeWhile(_ == ' ').length

  private def _value(value: String): String = value.trim.stripPrefix("\"").stripSuffix("\"")

  private def _development_deployment_id(sourceid: String): String =
    if (sourceid == StandaloneDevelopmentLaunchProfile.STANDALONE_DEPLOYMENT_ID)
      StandaloneDevelopmentLaunchProfile.STANDALONE_DEPLOYMENT_ID
    else
      s"DEV:$sourceid"

  private def _local_repository_root(catalogroot: String): Option[String] =
    Try(Path.of(catalogroot).toAbsolutePath.normalize.getParent.getParent.resolve("car").toString).toOption

  private def _public_repository_root(catalogbaseurl: String): Option[String] = {
    val suffix = "/catalog/car"
    Option.when(catalogbaseurl.stripSuffix("/").endsWith(suffix)) {
      catalogbaseurl.stripSuffix("/").dropRight(suffix.length) + "/car"
    }
  }
}

trait StandaloneSupervisorChild {
  def instanceId: String
  def port: Option[Int]
  def isAlive: Boolean
  def diagnostic: Option[String] = None
  def awaitReadiness(): Boolean = isAlive
  def stop(): Boolean
}

trait StandaloneSupervisorProcessHost {
  def preflight(profile: StandaloneLaunchProfile, owned: Option[StandaloneSupervisorChild]): Either[String, Unit]
  def start(profile: StandaloneLaunchProfile, instanceId: String): Either[String, StandaloneSupervisorChild]
  def recover(profile: StandaloneLaunchProfile, instanceId: String): Either[String, Option[StandaloneSupervisorChild]] =
    Left(StandaloneSupervisorProcessHost.RECOVERY_UNAVAILABLE)
}

object StandaloneSupervisorProcessHost {
  val EXECUTION_UNAVAILABLE = "supervisor-execution-unavailable"
  val PORT_UNAVAILABLE = "supervisor-port-unavailable"
  val RECOVERY_UNAVAILABLE = "supervisor-recovery-unavailable"
  val RECOVERY_EVIDENCE_UNAVAILABLE = "supervisor-launcher-evidence-unavailable"
  val RECOVERY_EVIDENCE_AMBIGUOUS = "supervisor-launcher-evidence-ambiguous"
  val RECOVERY_EVIDENCE_MISMATCH = "supervisor-launcher-evidence-mismatch"
  val RECOVERY_EVIDENCE_UNSAFE = "supervisor-launcher-evidence-unsafe"
  val STARTUP_READINESS_TIMEOUT_MILLIS = 15000L
  private val _startup_readiness_poll_millis = 100L
  private val _capture_poll_millis = 25L
  private val _shutdown_wait_millis = 5000L
  private val _shutdown_poll_millis = 50L
  private val _diagnostic_drain_wait_millis = 1000L
  private val MAXIMUM_DIAGNOSTIC_BYTES = 16 * 1024

  /** Standalone host process provider and process-ownership boundary. */
  object System extends StandaloneSupervisorProcessHost {
    def preflight(profile: StandaloneLaunchProfile, owned: Option[StandaloneSupervisorChild]): Either[String, Unit] =
      profile.defaultPort match {
        case Some(port) if owned.exists(child => child.isAlive && child.port.contains(port)) || _port_available(port) => Right(())
        case Some(_) => Left(PORT_UNAVAILABLE)
        case None => Right(())
      }

    def start(profile: StandaloneLaunchProfile, instanceId: String): Either[String, StandaloneSupervisorChild] =
      Try {
        val childinstanceid = instanceId
        val process = new ProcessBuilder(command(profile, instanceId)*) // cncf-car-lint: ignore documented standalone provider/bootstrap host boundary owns child-process creation.
          .redirectOutput(ProcessBuilder.Redirect.INHERIT) // cncf-car-lint: ignore documented standalone provider/bootstrap host boundary owns child-process output routing.
          .redirectError(ProcessBuilder.Redirect.PIPE) // cncf-car-lint: ignore documented standalone provider/bootstrap host boundary owns child-process diagnostics.
        profile.workingDirectory.foreach(directory => process.directory(directory.toFile))
        _sanitize_environment(process.environment())
        val started = process.start()
        new SystemChild(childinstanceid, profile.defaultPort, started)
      }.toEither.left.map(_ => EXECUTION_UNAVAILABLE)

    override def recover(profile: StandaloneLaunchProfile, instanceId: String): Either[String, Option[StandaloneSupervisorChild]] =
      _recover(profile, instanceId, _launcher_evidence_path)

    private[impl] def _recover(
      profile: StandaloneLaunchProfile,
      instanceid: String,
      evidencepath: Path
    ): Either[String, Option[StandaloneSupervisorChild]] =
      _read_evidence(evidencepath).flatMap { entries =>
        _select_evidence(entries, profile, instanceid).flatMap {
          case entry if entry.stoppedAt.nonEmpty => Right(None)
          case entry =>
            for {
              pid <- entry.processId.toRight(RECOVERY_EVIDENCE_UNSAFE)
              startedat <- entry.processStartedAt.toRight(RECOVERY_EVIDENCE_UNSAFE)
              handle <- ProcessHandle.of(pid).toScala.toRight(RECOVERY_EVIDENCE_UNSAFE)
              _ <- Either.cond(handle.isAlive, (), RECOVERY_EVIDENCE_UNSAFE)
              _ <- Either.cond(handle.info.startInstant.toScala.contains(startedat), (), RECOVERY_EVIDENCE_UNSAFE)
            } yield Some(new RecoveredSystemChild(entry.instanceId, profile.defaultPort, handle))
        }
      }

    private[impl] def _child(
      process: Process,
      instanceid: String,
      port: Option[Int]
    ): StandaloneSupervisorChild =
      new SystemChild(instanceid, port, process)

    private val _launcher_evidence_schema = "cncf.launcher.server-evidence.v1"

    private final case class LauncherEvidenceEntry(
      instanceId: String,
      target: String,
      artifactId: Option[String],
      executionMode: String,
      developmentDirectory: Option[String],
      lastSeenAt: Instant,
      stoppedAt: Option[Instant],
      processId: Option[Long],
      processStartedAt: Option[Instant]
    )

    private final case class LauncherEvidenceSnapshot(
      schema: String,
      entries: Vector[LauncherEvidenceEntry]
    )

    private given Decoder[LauncherEvidenceEntry] = deriveDecoder
    private given Decoder[LauncherEvidenceSnapshot] = deriveDecoder

    private def _launcher_evidence_path: Path =
      Path.of(java.lang.System.getProperty("user.home"), ".cncf", "launcher", "server-evidence.json") // cncf-car-lint: ignore documented standalone provider/bootstrap host boundary locates Launcher-owned evidence.

    private def _read_evidence(path: Path): Either[String, Vector[LauncherEvidenceEntry]] =
      if (!Files.isRegularFile(path))
        Left(RECOVERY_EVIDENCE_UNAVAILABLE)
      else
        Try(Files.readString(path, StandardCharsets.UTF_8)).toEither.left.map(_ => RECOVERY_EVIDENCE_UNAVAILABLE) // cncf-car-lint: ignore documented standalone provider/bootstrap host boundary reads Launcher-owned evidence.
          .flatMap(text => decode[LauncherEvidenceSnapshot](text).left.map(_ => RECOVERY_EVIDENCE_UNAVAILABLE))
          .filterOrElse(_.schema == _launcher_evidence_schema, RECOVERY_EVIDENCE_UNAVAILABLE)
          .map(_.entries)

    private def _select_evidence(
      entries: Vector[LauncherEvidenceEntry],
      profile: StandaloneLaunchProfile,
      instanceid: String
    ): Either[String, LauncherEvidenceEntry] = {
      val exact = entries.filter(_.instanceId == instanceid)
      if (exact.size == 1)
        _validate_evidence_identity(exact.head, profile).map(_ => exact.head)
      else if (exact.size > 1)
        Left(RECOVERY_EVIDENCE_AMBIGUOUS)
      else {
        val candidates = entries.filter(entry => entry.stoppedAt.nonEmpty && _matches_profile(entry, profile))
        candidates.lastOption.toRight(RECOVERY_EVIDENCE_MISMATCH)
      }
    }

    private def _validate_evidence_identity(
      entry: LauncherEvidenceEntry,
      profile: StandaloneLaunchProfile
    ): Either[String, Unit] =
      Either.cond(_matches_profile(entry, profile), (), RECOVERY_EVIDENCE_MISMATCH)

    private def _matches_profile(entry: LauncherEvidenceEntry, profile: StandaloneLaunchProfile): Boolean =
      entry.artifactId.contains(profile.targetId) &&
        (profile match {
          case development: StandaloneDevelopmentLaunchProfile =>
            entry.executionMode == "development" &&
              entry.developmentDirectory.flatMap(value => Try(Path.of(value).toAbsolutePath.normalize).toOption).contains(development.developmentDirectory.toAbsolutePath.normalize)
          case _: StandaloneRepositoryLaunchProfile => entry.executionMode == "repository"
        })

    private final class RecoveredSystemChild(
      val instanceId: String,
      val port: Option[Int],
      root: ProcessHandle
    ) extends StandaloneSupervisorChild {
      private val _tracked = scala.collection.mutable.LinkedHashSet.empty[ProcessHandle]

      _tracked += root
      _refresh_descendants()
      private val _capture_thread = {
        val thread = new Thread(() => _capture_descendants(), "textus-supervisor-recovered-process-tree") // cncf-car-lint: ignore documented standalone provider/bootstrap host boundary owns recovered child-process observation.
        thread.setDaemon(true)
        thread.start()
        thread
      }

      def isAlive: Boolean = {
        _refresh_descendants()
        _tracked.synchronized(_tracked.exists(_.isAlive))
      }

      def stop(): Boolean = {
        _refresh_descendants()
        val handles = _tracked.synchronized(_tracked.toVector)
        _signal(handles, force = false)
        _await_dead(handles, _shutdown_wait_millis)
        _refresh_descendants()
        val remaining = _tracked.synchronized(_tracked.toVector.filter(_.isAlive))
        _signal(remaining, force = true)
        _await_dead(remaining, _shutdown_wait_millis)
        !isAlive
      }

      private def _refresh_descendants(): Unit =
        _tracked.synchronized {
          Try(root.descendants().iterator().asScala.foreach(_tracked += _))
        }

      private def _capture_descendants(): Unit = {
        var running = true
        while (running) {
          _refresh_descendants()
          running = root.isAlive
          if (running) {
            try Thread.sleep(_capture_poll_millis) // cncf-car-lint: ignore documented standalone provider/bootstrap host boundary polls recovered child-process ownership.
            catch {
              case _: InterruptedException =>
                Thread.currentThread.interrupt()
                running = false
            }
          }
        }
      }

      private def _signal(handles: Vector[ProcessHandle], force: Boolean): Unit = {
        val descendants = handles.filterNot(_ == root)
        val ordered = descendants ++ handles.find(_ == root).toVector
        ordered.filter(_.isAlive).foreach(handle => Try(if (force) handle.destroyForcibly() else handle.destroy()))
      }

      private def _await_dead(handles: Vector[ProcessHandle], timeoutmillis: Long): Unit = {
        val deadline = java.lang.System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutmillis)
        while (handles.exists(_.isAlive) && java.lang.System.nanoTime() < deadline) {
          try Thread.sleep(_shutdown_poll_millis) // cncf-car-lint: ignore documented standalone provider/bootstrap host boundary waits for recovered child-process shutdown.
          catch {
            case _: InterruptedException =>
              Thread.currentThread.interrupt()
              return
          }
        }
      }
    }

    private final class SystemChild(
      val instanceId: String,
      val port: Option[Int],
      process: Process
    ) extends StandaloneSupervisorChild {
      private val _root = process.toHandle
      private val _tracked = scala.collection.mutable.LinkedHashSet.empty[ProcessHandle]
      private val _stderr = new ByteArrayOutputStream(MAXIMUM_DIAGNOSTIC_BYTES)

      _tracked += _root
      _refresh_descendants()
      private val _capture_thread = {
        val thread = new Thread(() => _capture_descendants(), "textus-supervisor-process-tree") // cncf-car-lint: ignore documented standalone provider/bootstrap host boundary owns child-process observation.
        thread.setDaemon(true)
        thread.start()
        thread
      }
      private val _diagnostic_thread = {
        val thread = new Thread(() => _capture_stderr(), "textus-supervisor-stderr") // cncf-car-lint: ignore documented standalone provider/bootstrap host boundary owns child-process diagnostic capture.
        thread.setDaemon(true)
        thread.start()
        thread
      }

      def isAlive: Boolean = {
        _refresh_descendants()
        _has_alive_tracked_processes
      }

      override def awaitReadiness(): Boolean =
        port match {
          case Some(port) => _await_port_bound(this, port)
          case None => isAlive
        }

      override def diagnostic: Option[String] = {
        if (!_root.isAlive && _diagnostic_thread.isAlive && Thread.currentThread != _diagnostic_thread) {
          try _diagnostic_thread.join(_diagnostic_drain_wait_millis)
          catch {
            case _: InterruptedException => Thread.currentThread.interrupt()
          }
        }
        _stderr.synchronized {
          Option(new String(_stderr.toByteArray, StandardCharsets.UTF_8).trim).filter(_.nonEmpty)
        }
      }

      def stop(): Boolean = {
        _refresh_descendants()
        val graceful = _tracked_processes
        _signal(graceful, force = false)
        _await_dead(graceful, _shutdown_wait_millis)
        _refresh_descendants()
        val remaining = _tracked_processes.filter(_.isAlive)
        _signal(remaining, force = true)
        _await_dead(_tracked_processes, _shutdown_wait_millis)
        _refresh_descendants()
        !_has_alive_tracked_processes
      }

      private def _refresh_descendants(): Unit =
        _tracked.synchronized {
          Try(_root.descendants().iterator().asScala.foreach(_tracked += _))
        }

      private def _capture_descendants(): Unit = {
        var running = true
        while (running) {
          _refresh_descendants()
          running = _root.isAlive
          if (running) {
            try Thread.sleep(_capture_poll_millis) // cncf-car-lint: ignore documented standalone provider/bootstrap host boundary polls child-process ownership.
            catch {
              case _: InterruptedException =>
                Thread.currentThread.interrupt()
                running = false
            }
          }
        }
      }

      private def _capture_stderr(): Unit = {
        val stream = process.getErrorStream
        val buffer = new Array[Byte](1024)
        try {
          var read = stream.read(buffer)
          while (read >= 0) {
            _stderr.synchronized {
              val remaining = MAXIMUM_DIAGNOSTIC_BYTES - _stderr.size
              if (remaining > 0)
                _stderr.write(buffer, 0, math.min(remaining, read))
            }
            read = stream.read(buffer)
          }
        } catch {
          case _: java.io.IOException => ()
        } finally {
          Try(stream.close())
        }
      }

      private def _has_alive_tracked_processes: Boolean =
        _tracked.synchronized(_tracked.exists(_.isAlive))

      private def _tracked_processes: Vector[ProcessHandle] =
        _tracked.synchronized(_tracked.toVector)

      private def _signal(handles: Vector[ProcessHandle], force: Boolean): Unit = {
        val descendants = handles.filterNot(_ == _root)
        val ordered = descendants ++ handles.find(_ == _root).toVector
        ordered.filter(_.isAlive).foreach { handle =>
          Try(if (force) handle.destroyForcibly() else handle.destroy())
        }
      }

      private def _await_dead(handles: Vector[ProcessHandle], timeoutmillis: Long): Unit = {
        val deadline = java.lang.System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutmillis)
        var interrupted = false
        while (!interrupted && handles.exists(_.isAlive) && java.lang.System.nanoTime() < deadline) {
          try Thread.sleep(_shutdown_poll_millis) // cncf-car-lint: ignore documented standalone provider/bootstrap host boundary waits for child-process shutdown.
          catch {
            case _: InterruptedException =>
              Thread.currentThread.interrupt()
              interrupted = true
          }
        }
      }
    }

    private[impl] def command(
      profile: StandaloneLaunchProfile,
      instanceId: String
    ): Vector[String] =
      profile match {
        case development: StandaloneDevelopmentLaunchProfile =>
          Vector(
            "cncf",
            development.developmentDirectory.toAbsolutePath.normalize.toString,
            "server",
            s"--textus.server.port=${development.developmentDefaultPort}",
            s"--textus.control-center.registration-instance-id=$instanceId"
          ) ++ Option.when(development.developmentRepositoryDirectories.nonEmpty) {
            s"--textus.repository.component.dev.dir=${development.developmentRepositoryDirectories.mkString(",")}"
          }.toVector
        case repository: StandaloneRepositoryLaunchProfile =>
          Vector(
            "cncf",
            "--name",
            profile.targetId,
            "--artifact-repository",
            repository.repositoryRoot,
            "server",
            s"--textus.control-center.registration-instance-id=$instanceId"
          )
      }

    private[impl] def _sanitize_environment(environment: java.util.Map[String, String]): Unit = {
      environment.remove("CNCF_LAUNCHER_ARGS_FILE")
      environment.remove("CNCF_LAUNCHER_DEV_DELEGATED")
    }

    private def _await_port_bound(child: SystemChild, port: Int): Boolean = {
      val deadline = java.lang.System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STARTUP_READINESS_TIMEOUT_MILLIS)
      var ready = false
      while (!ready && child.isAlive && java.lang.System.nanoTime() < deadline) {
        ready = !_port_available(port)
        if (!ready) {
          try Thread.sleep(_startup_readiness_poll_millis) // cncf-car-lint: ignore documented standalone provider/bootstrap host boundary polls child-process readiness.
          catch {
            case _: InterruptedException =>
              Thread.currentThread.interrupt()
              return false
          }
        }
      }
      ready && child.isAlive
    }

    private def _port_available(port: Int): Boolean =
      Try {
        val socket = new ServerSocket()
        try {
          socket.setReuseAddress(false)
          socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress, port))
          true
        } finally socket.close()
      }.getOrElse(false)
  }
}

final case class StandaloneSupervisorRequestRecord(
  request: SupervisorRequest,
  result: SupervisorResult
)

final case class StandaloneSupervisorSnapshot(
  schemaVersion: String,
  supervisorId: String,
  records: Vector[StandaloneSupervisorRequestRecord],
  ownedInstances: Map[String, String]
)

private sealed trait StandaloneOwnershipRecovery {
  def snapshot: StandaloneSupervisorSnapshot
}

private final case class StandaloneOwnershipLive(
  snapshot: StandaloneSupervisorSnapshot,
  child: StandaloneSupervisorChild
) extends StandaloneOwnershipRecovery

private final case class StandaloneOwnershipCleared(
  snapshot: StandaloneSupervisorSnapshot,
  previous: Option[StandaloneSupervisorChild]
) extends StandaloneOwnershipRecovery

private final case class StandaloneOwnershipUnavailable(
  snapshot: StandaloneSupervisorSnapshot,
  instanceid: Option[String],
  diagnosticcode: String
) extends StandaloneOwnershipRecovery

final class StandaloneSupervisorStateStore(path: Path, supervisorId: String) {
  import StandaloneSupervisorStateStore.*
  import StandaloneSupervisorStateStore.given

  def load(): Either[String, StandaloneSupervisorSnapshot] =
    if (!Files.isRegularFile(path))
      Right(StandaloneSupervisorSnapshot(SCHEMA_VERSION, supervisorId, Vector.empty, Map.empty))
    else
      Try(Files.readString(path, StandardCharsets.UTF_8)).toEither.left.map(_ => STATE_UNAVAILABLE) // cncf-car-lint: ignore documented standalone provider/bootstrap host boundary reads its private durable snapshot.
        .flatMap(text => decode[StandaloneSupervisorSnapshot](text).left.map(_ => STATE_UNAVAILABLE))
        .filterOrElse(snapshot => snapshot.schemaVersion == SCHEMA_VERSION && snapshot.supervisorId == supervisorId, STATE_UNAVAILABLE)

  def save(snapshot: StandaloneSupervisorSnapshot): Either[String, Unit] =
    Try {
      Files.createDirectories(path.getParent) // cncf-car-lint: ignore documented standalone provider/bootstrap host boundary prepares its private durable snapshot directory.
      val temporary = Files.createTempFile(path.getParent, "textus-supervisor-state-", ".json") // cncf-car-lint: ignore documented standalone provider/bootstrap host boundary creates a private durable snapshot replacement.
      try {
        Files.writeString(temporary, snapshot.asJson.noSpaces + "\n", StandardCharsets.UTF_8) // cncf-car-lint: ignore documented standalone provider/bootstrap host boundary writes its private durable snapshot.
        try Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) // cncf-car-lint: ignore documented standalone provider/bootstrap host boundary atomically replaces its private durable snapshot.
        catch {
          case _: java.nio.file.AtomicMoveNotSupportedException =>
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING) // cncf-car-lint: ignore documented standalone provider/bootstrap host boundary replaces its private durable snapshot when atomic move is unavailable.
        }
      } finally Files.deleteIfExists(temporary) // cncf-car-lint: ignore documented standalone provider/bootstrap host boundary removes its private temporary snapshot.
    }.map(_ => ()).toEither.left.map(_ => STATE_UNAVAILABLE)
}

object StandaloneSupervisorStateStore {
  val SCHEMA_VERSION = "textus.supervisor.standalone-state.v1"
  val STATE_UNAVAILABLE = "supervisor-state-unavailable"

  given Encoder[SupervisorAction] = Encoder.encodeString.contramap(_.toString)
  given Decoder[SupervisorAction] = Decoder.decodeString.emap {
    case "Start" => Right(SupervisorAction.Start)
    case "Stop" => Right(SupervisorAction.Stop)
    case "Restart" => Right(SupervisorAction.Restart)
    case value => Left(s"Unknown supervisor action: $value")
  }
  given Encoder[SupervisorState] = Encoder.encodeString.contramap(_.toString)
  given Decoder[SupervisorState] = Decoder.decodeString.emap {
    case "Queued" => Right(SupervisorState.Queued)
    case "Accepted" => Right(SupervisorState.Accepted)
    case "Running" => Right(SupervisorState.Running)
    case "Stopped" => Right(SupervisorState.Stopped)
    case "Rejected" => Right(SupervisorState.Rejected)
    case "Failed" => Right(SupervisorState.Failed)
    case "TimedOut" => Right(SupervisorState.TimedOut)
    case value => Left(s"Unknown supervisor state: $value")
  }
  given Encoder[SupervisorRequest] = deriveEncoder
  given Decoder[SupervisorRequest] = deriveDecoder
  given Encoder[SupervisorResult] = deriveEncoder
  given Decoder[SupervisorResult] = deriveDecoder
  given Encoder[StandaloneSupervisorRequestRecord] = deriveEncoder
  given Decoder[StandaloneSupervisorRequestRecord] = deriveDecoder
  given Encoder[StandaloneSupervisorSnapshot] = deriveEncoder
  given Decoder[StandaloneSupervisorSnapshot] = deriveDecoder
}

final class StandaloneTextusSupervisor(
  statePath: Path,
  processHost: StandaloneSupervisorProcessHost = StandaloneSupervisorProcessHost.System,
  supervisorId: String = StandaloneTextusSupervisor.SUPERVISOR_ID
) extends Supervisor {
  private val _store = new StandaloneSupervisorStateStore(statePath, supervisorId)
  private val _lock = new Object
  private var _profiles = Map.empty[(String, String), StandaloneLaunchProfile]
  private var _repository_profiles = Map.empty[String, StandaloneRepositoryLaunchProfile]
  private var _children = Map.empty[String, StandaloneSupervisorChild]
  private var _state = _store.load()

  def registerDevelopmentProfile(profile: StandaloneDevelopmentLaunchProfile): Unit =
    _lock.synchronized {
      _profiles = _profiles.updated((profile.targetId, profile.deploymentId), profile)
    }

  def registerRepositoryProfile(profile: StandaloneRepositoryLaunchProfile): Unit =
    _lock.synchronized {
      _repository_profiles = _repository_profiles.updated(profile.deploymentId, profile)
    }

  def submit(request: SupervisorRequest)(using ExecutionContext): Consequence[SupervisorResult] =
    Consequence.success(_lock.synchronized(_submit(request)))

  def lookup(requestId: String)(using ExecutionContext): Consequence[Option[SupervisorResult]] =
    Consequence.success(_lock.synchronized {
      _state.toOption.flatMap(_.records.find(_.result.requestId == requestId).map(_.result))
    })

  private def _submit(request: SupervisorRequest)(using context: ExecutionContext): SupervisorResult = {
    val now = context.clock.instant()
    _state match {
      case Left(code) => _result(request, SupervisorState.Rejected, Some(code), None, now)
      case Right(snapshot) =>
        _existing(snapshot, request).getOrElse {
          if (request.deadlineAt.isBefore(now))
            _persist(snapshot, request, _result(request, SupervisorState.TimedOut, Some("supervisor-request-timed-out"), None, now))
          else if (request.action == SupervisorAction.Stop)
            _stop(snapshot, request, _profile(request).orElse(_owned_profile(snapshot, request)), now)
          else
            _profile(request) match {
              case None => _persist(snapshot, request, _result(request, SupervisorState.Rejected, Some(StandaloneDevelopmentLaunchProfile.PROFILE_UNAVAILABLE), None, now))
              case Some(profile) => _execute(snapshot, request, profile, now)
            }
        }
    }
  }

  private def _execute(
    snapshot: StandaloneSupervisorSnapshot,
    request: SupervisorRequest,
    profile: StandaloneLaunchProfile,
    now: java.time.Instant
  )(using context: ExecutionContext): SupervisorResult =
    request.action match {
      case SupervisorAction.Start =>
        _reconcile_owned(snapshot, request.targetId, profile) match {
          case StandaloneOwnershipLive(current, child) =>
            _persist(current, request, _result(request, SupervisorState.Rejected, Some("supervisor-ownership-unavailable"), Some(child.instanceId), now))
          case StandaloneOwnershipCleared(current, previous) =>
            _start(current, request, profile, previous, now)
          case StandaloneOwnershipUnavailable(current, instanceid, code) =>
            _persist(current, request, _result(request, SupervisorState.Rejected, Some(code), instanceid, now))
        }
      case SupervisorAction.Stop =>
        _stop(snapshot, request, Some(profile), now)
      case SupervisorAction.Restart =>
        _reconcile_owned(snapshot, request.targetId, profile) match {
          case StandaloneOwnershipLive(current, child) =>
            processHost.preflight(profile, Some(child)).fold(
              code => _persist(current, request, _result(request, SupervisorState.Rejected, Some(code), Some(child.instanceId), now)),
              _ =>
                if (!child.stop())
                  _persist(current, request, _result(request, SupervisorState.Rejected, Some(StandaloneSupervisorProcessHost.EXECUTION_UNAVAILABLE), Some(child.instanceId), now))
                else {
                  _children = _children.removed(request.targetId)
                  _start(current.copy(ownedInstances = current.ownedInstances.removed(request.targetId)), request, profile, Some(child), now)
                }
            )
          case StandaloneOwnershipCleared(current, previous) =>
            _start(current, request, profile, previous, now)
          case StandaloneOwnershipUnavailable(current, instanceid, code) =>
            _persist(current, request, _result(request, SupervisorState.Rejected, Some(code), instanceid, now))
        }
    }

  private def _stop(
    snapshot: StandaloneSupervisorSnapshot,
    request: SupervisorRequest,
    profile: Option[StandaloneLaunchProfile],
    now: java.time.Instant
  ): SupervisorResult =
    profile match {
      case Some(value) => _reconcile_owned(snapshot, request.targetId, value) match {
        case StandaloneOwnershipLive(current, child) if child.stop() =>
          _children = _children.removed(request.targetId)
          _persist(current.copy(ownedInstances = current.ownedInstances.removed(request.targetId)), request, _result(request, SupervisorState.Stopped, None, Some(child.instanceId), now))
        case StandaloneOwnershipCleared(current, _) =>
          _persist(current, request, _result(request, SupervisorState.Stopped, None, snapshot.ownedInstances.get(request.targetId), now))
        case StandaloneOwnershipUnavailable(current, instanceid, code) =>
          _persist(current, request, _result(request, SupervisorState.Rejected, Some(code), instanceid, now))
        case StandaloneOwnershipLive(current, child) =>
          _persist(current, request, _result(request, SupervisorState.Rejected, Some(StandaloneSupervisorProcessHost.EXECUTION_UNAVAILABLE), Some(child.instanceId), now))
      }
      case None =>
        _children.get(request.targetId).filter(_.isAlive) match {
          case Some(child) if child.stop() =>
            _children = _children.removed(request.targetId)
            _persist(snapshot.copy(ownedInstances = snapshot.ownedInstances.removed(request.targetId)), request, _result(request, SupervisorState.Stopped, None, Some(child.instanceId), now))
          case _ =>
            _persist(snapshot, request, _result(request, SupervisorState.Rejected, Some("supervisor-ownership-unavailable"), snapshot.ownedInstances.get(request.targetId), now))
        }
    }

  private def _reconcile_owned(
    snapshot: StandaloneSupervisorSnapshot,
    targetid: String,
    profile: StandaloneLaunchProfile
  ): StandaloneOwnershipRecovery =
    _children.get(targetid) match {
      case Some(child) if child.isAlive => StandaloneOwnershipLive(snapshot, child)
      case Some(child) =>
        _children = _children.removed(targetid)
        StandaloneOwnershipCleared(snapshot.copy(ownedInstances = snapshot.ownedInstances.removed(targetid)), Some(child))
      case None => snapshot.ownedInstances.get(targetid) match {
        case None => StandaloneOwnershipCleared(snapshot, None)
        case Some(instanceid) => processHost.recover(profile, instanceid) match {
          case Right(Some(child)) if child.isAlive =>
            _children = _children.updated(targetid, child)
            StandaloneOwnershipLive(snapshot, child)
          case Right(Some(_)) =>
            StandaloneOwnershipUnavailable(snapshot, Some(instanceid), StandaloneSupervisorProcessHost.RECOVERY_EVIDENCE_UNSAFE)
          case Right(None) =>
            StandaloneOwnershipCleared(snapshot.copy(ownedInstances = snapshot.ownedInstances.removed(targetid)), None)
          case Left(code) =>
            StandaloneOwnershipUnavailable(snapshot, Some(instanceid), code)
        }
      }
    }

  private def _start(
    snapshot: StandaloneSupervisorSnapshot,
    request: SupervisorRequest,
    profile: StandaloneLaunchProfile,
    previous: Option[StandaloneSupervisorChild],
    now: java.time.Instant
  )(using context: ExecutionContext): SupervisorResult =
    processHost.preflight(profile, previous).fold(
      code => _persist(snapshot, request, _result(request, SupervisorState.Rejected, Some(code), None, now)),
      _ => _launch(snapshot, request, profile, now)
    )

  private def _launch(
    snapshot: StandaloneSupervisorSnapshot,
    request: SupervisorRequest,
    profile: StandaloneLaunchProfile,
    now: java.time.Instant
  )(using context: ExecutionContext): SupervisorResult = {
    val instanceid = context.idGeneration.opaqueId("supervisor-instance")
    val accepted = _result(request, SupervisorState.Accepted, None, Some(instanceid), now)
    val reserved = snapshot.copy(ownedInstances = snapshot.ownedInstances.updated(request.targetId, instanceid))
    _save(reserved.copy(records = reserved.records :+ StandaloneSupervisorRequestRecord(request, accepted))) match {
      case Left(code) => _result(request, SupervisorState.Rejected, Some(code), None, now)
      case Right(saved) =>
        processHost.start(profile, instanceid) match {
          case Right(child) if child.isAlive && child.awaitReadiness() =>
            _children = _children.updated(request.targetId, child)
            accepted
          case Right(child) =>
            child.stop()
            _fail_reserved(saved, request, instanceid, now, child.diagnostic)
          case Left(_) =>
            _fail_reserved(saved, request, instanceid, now)
        }
    }
  }

  private def _fail_reserved(
    snapshot: StandaloneSupervisorSnapshot,
    request: SupervisorRequest,
    instanceid: String,
    now: java.time.Instant,
    diagnostic: Option[String] = None
  ): SupervisorResult = {
    val failed = _result(request, SupervisorState.Failed, Some(StandaloneSupervisorProcessHost.EXECUTION_UNAVAILABLE), Some(instanceid), now, diagnostic)
    val updated = snapshot.copy(
      records = snapshot.records.filterNot(_.request.requestId == request.requestId) :+ StandaloneSupervisorRequestRecord(request, failed),
      ownedInstances = snapshot.ownedInstances.removed(request.targetId)
    )
    _save(updated).fold(code => _result(request, SupervisorState.Rejected, Some(code), None, now), _ => failed)
  }

  private def _existing(snapshot: StandaloneSupervisorSnapshot, request: SupervisorRequest): Option[SupervisorResult] =
    snapshot.records.find(record =>
      record.request.requestId == request.requestId &&
        record.request.idempotencyKey == request.idempotencyKey
    ).map(_.result)

  private def _profile(request: SupervisorRequest): Option[StandaloneLaunchProfile] =
    request.deploymentId
      .flatMap { deploymentid =>
        _profiles.get(request.targetId -> deploymentid)
          .orElse(_repository_profiles.get(deploymentid).map(_.forTarget(request.targetId)))
      }

  private def _owned_profile(
    snapshot: StandaloneSupervisorSnapshot,
    request: SupervisorRequest
  ): Option[StandaloneLaunchProfile] =
    snapshot.ownedInstances.get(request.targetId).flatMap { instanceid =>
      snapshot.records.reverseIterator
        .find(record => record.request.targetId == request.targetId && record.result.instanceId.contains(instanceid))
        .flatMap(record => _profile(record.request))
    }

  private def _persist(
    snapshot: StandaloneSupervisorSnapshot,
    request: SupervisorRequest,
    result: SupervisorResult
  ): SupervisorResult =
    _save(snapshot.copy(records = snapshot.records :+ StandaloneSupervisorRequestRecord(request, result)))
      .fold(code => result.copy(state = SupervisorState.Rejected, diagnosticCode = Some(code), diagnostic = Some(code)), _ => result)

  private def _save(snapshot: StandaloneSupervisorSnapshot): Either[String, StandaloneSupervisorSnapshot] =
    _store.save(snapshot).map { _ =>
      _state = Right(snapshot)
      snapshot
    }.left.map { code =>
      _state = Left(code)
      code
    }

  private def _result(
    request: SupervisorRequest,
    state: SupervisorState,
    diagnosticcode: Option[String],
    instanceid: Option[String],
    now: java.time.Instant,
    diagnostic: Option[String] = None
  ): SupervisorResult =
    SupervisorResult(
      request.requestId,
      state,
      diagnosticcode,
      diagnostic.orElse(diagnosticcode),
      supervisorId,
      instanceid,
      Some(now),
      Option.when(state == SupervisorState.Stopped || state == SupervisorState.Rejected || state == SupervisorState.Failed || state == SupervisorState.TimedOut)(now)
    )
}

object StandaloneTextusSupervisor {
  val SUPERVISOR_ID = "textus-supervisor-standalone"
  private val _instances = scala.collection.concurrent.TrieMap.empty[Path, StandaloneTextusSupervisor]

  def forHome(home: Path): StandaloneTextusSupervisor = {
    val normalized = home.toAbsolutePath.normalize
    _instances.getOrElseUpdate(
      normalized,
      new StandaloneTextusSupervisor(normalized.resolve("textus-supervisor").resolve("state.json"))
    )
  }
}
