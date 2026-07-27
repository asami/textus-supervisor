/*
 * @since   Jul. 28, 2026
 * @version Jul. 28, 2026
 * @author  ASAMI, Tomoharu
 */
package org.simplemodeling.textus.supervisor.impl

import java.net.{InetAddress, InetSocketAddress, ServerSocket}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import scala.util.Try

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*
import io.circe.parser.decode
import io.circe.syntax.*
import org.goldenport.Consequence
import org.goldenport.configuration.ResolvedConfiguration
import org.goldenport.cncf.config.ConfigurationAccess
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.spi.supervisor.{Supervisor, SupervisorAction, SupervisorRequest, SupervisorResult, SupervisorState}

final case class StandaloneDevelopmentLaunchProfile(
  targetId: String,
  developmentDirectory: Path,
  defaultPort: Int
)

object StandaloneDevelopmentLaunchProfile {
  val PROFILE_UNAVAILABLE = "supervisor-launch-profile-unavailable"

  def discover(developmentDirectory: Path): Option[StandaloneDevelopmentLaunchProfile] = {
    val descriptor = developmentDirectory.toAbsolutePath.normalize.resolve("project.yaml")
    Try(Files.readString(descriptor, StandardCharsets.UTF_8)).toOption
      .flatMap(_section_value(_, Vector("project"), "name"))
      .flatMap(resolve(_, developmentDirectory).toOption)
  }

  def resolve(targetId: String, developmentDirectory: Path): Either[String, StandaloneDevelopmentLaunchProfile] = {
    val directory = developmentDirectory.toAbsolutePath.normalize
    val descriptor = directory.resolve("project.yaml")
    if (!Files.isDirectory(directory) || !Files.isRegularFile(descriptor))
      Left(PROFILE_UNAVAILABLE)
    else
      Try(Files.readString(descriptor, StandardCharsets.UTF_8)).toEither.left.map(_ => PROFILE_UNAVAILABLE).flatMap { document =>
        for {
          projectname <- _section_value(document, Vector("project"), "name").filter(_ == targetId).toRight(PROFILE_UNAVAILABLE)
          _ <- _section_value(document, Vector("project"), "kind").filter(_ == "car").toRight(PROFILE_UNAVAILABLE)
          porttext <- _section_value(document, Vector("project", "component", "config"), "textus.server.default-port").toRight(PROFILE_UNAVAILABLE)
          port <- Try(porttext.toInt).toOption.filter(value => value >= 1 && value <= 65535).toRight(PROFILE_UNAVAILABLE)
        } yield StandaloneDevelopmentLaunchProfile(projectname, directory, port)
      }
  }

  private def _section_value(document: String, sections: Vector[String], key: String): Option[String] = {
    val lines = document.linesIterator.toVector
    val targetindent = sections.size * 2
    val sectionstart = sections.foldLeft(Option(0 -> lines)) {
      case (Some((baseindent, remaining)), section) =>
        val index = remaining.indexWhere(line => line.takeWhile(_ == ' ').length == baseindent && line.trim == s"$section:")
        Option.when(index >= 0)((baseindent + 2) -> remaining.drop(index + 1))
      case _ => None
    }
    sectionstart.flatMap { case (_, remaining) =>
      remaining.takeWhile(line => line.trim.isEmpty || line.takeWhile(_ == ' ').length >= targetindent).collectFirst {
        case line if line.takeWhile(_ == ' ').length == targetindent && line.trim.startsWith(s"$key:") =>
          line.trim.stripPrefix(s"$key:").trim.stripPrefix("\"").stripSuffix("\"")
      }
    }.filter(_.nonEmpty)
  }
}

object StandaloneDevelopmentProfileRegistry {
  val CONTROL_CENTER_HOME_KEY = "textus-control-center.home"
  val DEVELOPMENT_ROOT_KEY = "textus-control-center.catalog.development.root"

  def registerConfigured(
    supervisor: StandaloneTextusSupervisor,
    configuration: ResolvedConfiguration
  ): Unit = {
    val directroots = ConfigurationAccess.getString(configuration, DEVELOPMENT_ROOT_KEY).toVector
    val catalogroots = ConfigurationAccess.getString(configuration, CONTROL_CENTER_HOME_KEY).toVector
      .flatMap(home => _catalog_roots(Path.of(home).resolve("catalog.yaml")))
    (directroots ++ catalogroots).map(Path.of(_).toAbsolutePath.normalize).distinct.foreach { root =>
      if (Files.isDirectory(root)) {
        val children = Files.list(root)
        try children.iterator().asScala
          .flatMap(StandaloneDevelopmentLaunchProfile.discover)
          .foreach(supervisor.registerDevelopmentProfile)
        finally children.close()
      }
    }
  }

  private def _catalog_roots(path: Path): Vector[String] =
    Try(Files.readString(path, StandardCharsets.UTF_8)).toOption.toVector.flatMap { document =>
      val lines = document.linesIterator.toVector
      val developmentindex = lines.indexWhere(_.trim == "development:")
      if (developmentindex < 0) Vector.empty
      else
        lines.drop(developmentindex + 1)
          .takeWhile(line => line.trim.isEmpty || line.takeWhile(_ == ' ').length > 0)
          .collect {
            case line if line.trim.startsWith("path:") =>
              line.trim.stripPrefix("path:").trim.stripPrefix("\"").stripSuffix("\"")
          }
          .filter(_.nonEmpty)
    }
}

trait StandaloneSupervisorChild {
  def instanceId: String
  def port: Int
  def isAlive: Boolean
  def stop(): Boolean
}

trait StandaloneSupervisorProcessHost {
  def preflight(profile: StandaloneDevelopmentLaunchProfile, owned: Option[StandaloneSupervisorChild]): Either[String, Unit]
  def start(profile: StandaloneDevelopmentLaunchProfile, instanceId: String): Either[String, StandaloneSupervisorChild]
}

object StandaloneSupervisorProcessHost {
  val EXECUTION_UNAVAILABLE = "supervisor-execution-unavailable"
  val PORT_UNAVAILABLE = "supervisor-port-unavailable"

  object System extends StandaloneSupervisorProcessHost {
    def preflight(profile: StandaloneDevelopmentLaunchProfile, owned: Option[StandaloneSupervisorChild]): Either[String, Unit] =
      if (owned.exists(child => child.isAlive && child.port == profile.defaultPort) || _port_available(profile.defaultPort))
        Right(())
      else
        Left(PORT_UNAVAILABLE)

    def start(profile: StandaloneDevelopmentLaunchProfile, instanceId: String): Either[String, StandaloneSupervisorChild] =
      Try {
        val childinstanceid = instanceId
        val process = new ProcessBuilder(command(profile, instanceId)*)
          .directory(profile.developmentDirectory.toFile)
          .redirectOutput(ProcessBuilder.Redirect.INHERIT)
          .redirectError(ProcessBuilder.Redirect.INHERIT)
          .start()
        new StandaloneSupervisorChild {
          def instanceId: String = childinstanceid
          def port: Int = profile.defaultPort
          def isAlive: Boolean = process.isAlive
          def stop(): Boolean = {
            if (process.isAlive) {
              process.destroy()
              try process.waitFor(5, TimeUnit.SECONDS)
              catch {
                case _: InterruptedException => Thread.currentThread.interrupt()
              }
              if (process.isAlive) {
                process.destroyForcibly()
                try process.waitFor(5, TimeUnit.SECONDS)
                catch {
                  case _: InterruptedException => Thread.currentThread.interrupt()
                }
              }
            }
            !process.isAlive
          }
        }
      }.toEither.left.map(_ => EXECUTION_UNAVAILABLE)

    private[impl] def command(
      profile: StandaloneDevelopmentLaunchProfile,
      instanceId: String
    ): Vector[String] =
      Vector(
        "cncf",
        "server",
        s"--textus.server.port=${profile.defaultPort}",
        s"--textus.control-center.registration-instance-id=$instanceId"
      )

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

final class StandaloneSupervisorStateStore(path: Path, supervisorId: String) {
  import StandaloneSupervisorStateStore.*
  import StandaloneSupervisorStateStore.given

  def load(): Either[String, StandaloneSupervisorSnapshot] =
    if (!Files.isRegularFile(path))
      Right(StandaloneSupervisorSnapshot(SCHEMA_VERSION, supervisorId, Vector.empty, Map.empty))
    else
      Try(Files.readString(path, StandardCharsets.UTF_8)).toEither.left.map(_ => STATE_UNAVAILABLE)
        .flatMap(text => decode[StandaloneSupervisorSnapshot](text).left.map(_ => STATE_UNAVAILABLE))
        .filterOrElse(snapshot => snapshot.schemaVersion == SCHEMA_VERSION && snapshot.supervisorId == supervisorId, STATE_UNAVAILABLE)

  def save(snapshot: StandaloneSupervisorSnapshot): Either[String, Unit] =
    Try {
      Files.createDirectories(path.getParent)
      val temporary = Files.createTempFile(path.getParent, "textus-supervisor-state-", ".json")
      try {
        Files.writeString(temporary, snapshot.asJson.noSpaces + "\n", StandardCharsets.UTF_8)
        try Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        catch {
          case _: java.nio.file.AtomicMoveNotSupportedException =>
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
      } finally Files.deleteIfExists(temporary)
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
  private var _profiles = Map.empty[String, StandaloneDevelopmentLaunchProfile]
  private var _children = Map.empty[String, StandaloneSupervisorChild]
  private var _state = _store.load()

  def registerDevelopmentProfile(profile: StandaloneDevelopmentLaunchProfile): Unit =
    _lock.synchronized {
      _profiles = _profiles.updated(profile.targetId, profile)
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
          else
            _profiles.get(request.targetId) match {
              case None => _persist(snapshot, request, _result(request, SupervisorState.Rejected, Some(StandaloneDevelopmentLaunchProfile.PROFILE_UNAVAILABLE), None, now))
              case Some(profile) => _execute(snapshot, request, profile, now)
            }
        }
    }
  }

  private def _execute(
    snapshot: StandaloneSupervisorSnapshot,
    request: SupervisorRequest,
    profile: StandaloneDevelopmentLaunchProfile,
    now: java.time.Instant
  )(using context: ExecutionContext): SupervisorResult =
    request.action match {
      case SupervisorAction.Start =>
        _children.get(request.targetId) match {
          case Some(child) if child.isAlive =>
            _persist(snapshot, request, _result(request, SupervisorState.Rejected, Some("supervisor-ownership-unavailable"), Some(child.instanceId), now))
          case Some(child) =>
            _children = _children.removed(request.targetId)
            _start(snapshot.copy(ownedInstances = snapshot.ownedInstances.removed(request.targetId)), request, profile, Some(child), now)
          case None if snapshot.ownedInstances.contains(request.targetId) =>
            _persist(snapshot, request, _result(request, SupervisorState.Rejected, Some("supervisor-ownership-unavailable"), snapshot.ownedInstances.get(request.targetId), now))
          case None =>
            _start(snapshot, request, profile, None, now)
        }
      case SupervisorAction.Stop =>
        _children.get(request.targetId).filter(_.isAlive) match {
          case Some(child) if child.stop() =>
            _children = _children.removed(request.targetId)
            _persist(snapshot.copy(ownedInstances = snapshot.ownedInstances.removed(request.targetId)), request, _result(request, SupervisorState.Stopped, None, Some(child.instanceId), now))
          case _ =>
            _persist(snapshot, request, _result(request, SupervisorState.Rejected, Some("supervisor-ownership-unavailable"), snapshot.ownedInstances.get(request.targetId), now))
        }
      case SupervisorAction.Restart =>
        _children.get(request.targetId).filter(_.isAlive) match {
          case Some(child) =>
            processHost.preflight(profile, Some(child)).fold(
              code => _persist(snapshot, request, _result(request, SupervisorState.Rejected, Some(code), Some(child.instanceId), now)),
              _ =>
                if (!child.stop())
                  _persist(snapshot, request, _result(request, SupervisorState.Rejected, Some(StandaloneSupervisorProcessHost.EXECUTION_UNAVAILABLE), Some(child.instanceId), now))
                else {
                  _children = _children.removed(request.targetId)
                  _start(snapshot.copy(ownedInstances = snapshot.ownedInstances.removed(request.targetId)), request, profile, Some(child), now)
                }
            )
          case None =>
            _persist(snapshot, request, _result(request, SupervisorState.Rejected, Some("supervisor-ownership-unavailable"), snapshot.ownedInstances.get(request.targetId), now))
        }
    }

  private def _start(
    snapshot: StandaloneSupervisorSnapshot,
    request: SupervisorRequest,
    profile: StandaloneDevelopmentLaunchProfile,
    previous: Option[StandaloneSupervisorChild],
    now: java.time.Instant
  )(using context: ExecutionContext): SupervisorResult =
    processHost.preflight(profile, previous).fold(
      code => _persist(snapshot, request, _result(request, SupervisorState.Rejected, Some(code), None, now)),
      _ => {
        val instanceid = context.idGeneration.opaqueId("supervisor-instance")
        val accepted = _result(request, SupervisorState.Accepted, None, Some(instanceid), now)
        val reserved = snapshot.copy(ownedInstances = snapshot.ownedInstances.updated(request.targetId, instanceid))
        _save(reserved.copy(records = reserved.records :+ StandaloneSupervisorRequestRecord(request, accepted))) match {
          case Left(code) => _result(request, SupervisorState.Rejected, Some(code), None, now)
          case Right(saved) =>
            processHost.start(profile, instanceid) match {
              case Right(child) if child.isAlive =>
                _children = _children.updated(request.targetId, child)
                accepted
              case Right(child) =>
                child.stop()
                _fail_reserved(saved, request, instanceid, now)
              case Left(_) =>
                _fail_reserved(saved, request, instanceid, now)
            }
        }
      }
    )

  private def _fail_reserved(
    snapshot: StandaloneSupervisorSnapshot,
    request: SupervisorRequest,
    instanceid: String,
    now: java.time.Instant
  ): SupervisorResult = {
    val failed = _result(request, SupervisorState.Failed, Some(StandaloneSupervisorProcessHost.EXECUTION_UNAVAILABLE), Some(instanceid), now)
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
    now: java.time.Instant
  ): SupervisorResult =
    SupervisorResult(
      request.requestId,
      state,
      diagnosticcode,
      diagnosticcode,
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
