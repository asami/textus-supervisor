/*
 * @since   Oct. 17, 2026
 * @version Oct. 17, 2026
 */
package org.simplemodeling.textus.supervisor.impl

import java.net.{InetAddress, InetSocketAddress, ServerSocket, Socket}
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.concurrent.{ConcurrentLinkedQueue, TimeUnit}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.{Try, Using}
import scala.util.control.NonFatal

import io.circe.{Decoder, Json}
import io.circe.generic.semiauto.deriveDecoder
import io.circe.parser.parse
import org.goldenport.cncf.spi.supervisor.{SupervisorAction, SupervisorRequest}

/** Fixture observations of real Launcher evidence; these do not confer ownership. */
final case class CanonicalProcessEvidence(
  instanceId: String,
  artifactId: Option[String],
  executionMode: String,
  developmentDirectory: Option[String],
  stoppedAt: Option[Instant],
  processId: Option[Long],
  processStartedAt: Option[Instant]
)

final class CanonicalSupervisorProcessFixture private (
  val componentRoot: Path,
  val runtimeRoot: Path,
  val launcherClasspath: String,
  val directory: Path
) extends AutoCloseable {
  val home: Path = directory.resolve("home")
  val statePath: Path = directory.resolve("supervisor-state.json")
  val temporaryDirectory: Path = directory.resolve("tmp")
  val runtimeConfig: Path = directory.resolve("runtime.yaml")
  private val _assembly = directory.resolve("assembly.yaml")
  private val _wrapper = componentRoot.resolve("target/canonical-process/bin/cncf")
  private val _previous_home = Option(System.getProperty("user.home"))
  private var _completed = false
  private val _acquired_children = new ConcurrentLinkedQueue[StandaloneSupervisorChild]()
  private val _process_host = new StandaloneSupervisorProcessHost {
    def preflight(profile: StandaloneLaunchProfile, owned: Option[StandaloneSupervisorChild]): Either[String, Unit] =
      StandaloneSupervisorProcessHost.System.preflight(profile, owned)

    def start(profile: StandaloneLaunchProfile, instanceId: String): Either[String, StandaloneSupervisorChild] =
      StandaloneSupervisorProcessHost.System.start(profile, instanceId).map { child =>
        _acquired_children.add(child)
        child
      }

    override def recover(profile: StandaloneLaunchProfile, instanceId: String): Either[String, Option[StandaloneSupervisorChild]] =
      StandaloneSupervisorProcessHost.System.recover(profile, instanceId)
  }
  private val _port_socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress)
  val port: Int = _port_socket.getLocalPort
  _port_socket.close()
  val profile: StandaloneDevelopmentLaunchProfile = StandaloneDevelopmentLaunchProfile(
    "textus-supervisor", componentRoot, port, developmentRepositoryDirectories = Vector(componentRoot))

  Files.createDirectories(home)
  Files.createDirectories(temporaryDirectory)
  Files.createDirectories(directory.resolve("local-data"))
  Files.writeString(_assembly,
    """subsystem: canonical-supervisor-process
      |version: 0.1.1-SNAPSHOT
      |components:
      |  - namespace: org.simplemodeling.textus
      |    id: Supervisor
      |    version: 0.1.1-SNAPSHOT
      |config:
      |  textus.subsystem.user-mode: standalone
      |security:
      |  authentication:
      |    convention: enabled
      |    fallback_privilege: disabled
      |    local_subject:
      |      id: canonical-process-local-operator
      |      roles:
      |        - operator
      |      capabilities:
      |        - operator
      |      security_level: operator
      |""".stripMargin)
  Files.writeString(runtimeConfig,
    s"textus.assembly.descriptor: ${_yaml(_assembly.toString)}\n" +
      s"textus.subsystem.file: ${_yaml(_assembly.toString)}\n" +
      s"textus.local-data.root: ${_yaml(directory.resolve("local-data").toString)}\n")
  Files.createDirectories(_wrapper.getParent)
  private val _wrapper_arguments = Vector(
    Path.of(System.getProperty("java.home"), "bin", "java").toString,
    s"-Duser.home=$home", s"-Djava.io.tmpdir=$temporaryDirectory",
    "-cp", launcherClasspath, "cncf.launcher.CncfLauncherMain",
    "--launcher-home", home.toString, "--runtime-dev-dir", runtimeRoot.toString,
    "--cncf-config", runtimeConfig.toString)
  Files.writeString(_wrapper, "#!/bin/sh\nexec " + _wrapper_arguments.map(_shell).mkString(" ") + " \"$@\"\n")
  if (!_wrapper.toFile.setExecutable(true, true))
    throw new IllegalStateException(s"Canonical prerequisite: cannot make wrapper executable: $_wrapper")
  System.setProperty("user.home", home.toString)

  def backend(): StandaloneTextusSupervisor = {
    val backend = new StandaloneTextusSupervisor(statePath, _process_host)
    backend.registerDevelopmentProfile(profile)
    backend
  }

  def request(suffix: String, action: SupervisorAction = SupervisorAction.Start): SupervisorRequest =
    SupervisorRequest("request-" + suffix, "key-" + suffix, profile.targetId, Some(profile.deploymentId),
      action, "canonical-process-operator", Instant.now().plusSeconds(90))

  def evidence(): Vector[CanonicalProcessEvidence] = {
    val path = home.resolve(".cncf/launcher/server-evidence.json")
    if (!Files.exists(path)) Vector.empty
    else {
      given Decoder[CanonicalProcessEvidence] = deriveDecoder
      val json = parse(Files.readString(path)).fold(error => throw error, identity)
      require(json.hcursor.get[String]("schema").toOption.contains("cncf.launcher.server-evidence.v1"),
        s"Canonical evidence prerequisite: unexpected schema at $path")
      json.hcursor.get[Vector[CanonicalProcessEvidence]]("entries").fold(error => throw error, identity)
    }
  }

  def safeList(): Json = {
    val output = directory.resolve("safe-list.json")
    val error = directory.resolve("safe-list.stderr")
    val process = new ProcessBuilder(_wrapper.toString, "launcher", "evidence", "list", "--format", "json")
      .directory(componentRoot.toFile).redirectOutput(output.toFile).redirectError(error.toFile).start()
    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroy()
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        require(process.waitFor(5, TimeUnit.SECONDS), s"Exact safe-list child still live; work retained at $directory")
      }
      throw new IllegalStateException(s"Canonical safe-list timed out; evidence retained at $directory")
    }
    require(process.exitValue() == 0, s"Canonical safe-list failed; output and stderr retained at $directory")
    parse(Files.readString(output)).fold(exception => throw exception, identity)
  }

  def portReady: Boolean = Try {
    val socket = new Socket()
    try {
      socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress, port), 500)
      true
    } finally socket.close()
  }.getOrElse(false)

  def portAvailable: Boolean = Try {
    val socket = new ServerSocket()
    try {
      socket.setReuseAddress(false)
      socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress, port))
      true
    } finally socket.close()
  }.getOrElse(false)

  def nativeAlive(entry: CanonicalProcessEvidence): Boolean =
    entry.processId.flatMap(pid => ProcessHandle.of(pid).toScala)
      .exists(handle => handle.isAlive && handle.info().startInstant().toScala == entry.processStartedAt)

  def awaitEvidence(instanceId: String, stopped: Boolean): CanonicalProcessEvidence = {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    var entry = evidence().find(_.instanceId == instanceId).filter(_.stoppedAt.nonEmpty == stopped)
    while (entry.isEmpty && System.nanoTime() < deadline) {
      Thread.sleep(50)
      entry = evidence().find(_.instanceId == instanceId).filter(_.stoppedAt.nonEmpty == stopped)
    }
    entry.getOrElse(throw new IllegalStateException(s"Canonical invocation $instanceId missing expected stopped=$stopped evidence at $directory"))
  }

  def complete(): Unit = _completed = true

  /** Preserve failing case work and restore only the scoped user.home property. */
  def close(): Unit = {
    var failures = Vector.empty[Throwable]
    def _attempt_(action: => Unit): Unit =
      try action catch { case NonFatal(error) => failures :+= error }

    try {
      try _attempt_ {
        if (Files.exists(statePath)) {
          val snapshot = new StandaloneSupervisorStateStore(statePath, StandaloneTextusSupervisor.SUPERVISOR_ID)
            .load().fold(code => throw new IllegalStateException(s"Cleanup state unavailable: $code at $directory"), identity)
          snapshot.ownedInstances.get(profile.targetId).foreach { instanceid =>
            StandaloneSupervisorProcessHost.System.recover(profile, instanceid) match {
              case Right(Some(child)) =>
                require(child.stop(), s"Exact owned child shutdown failed: $instanceid at $directory")
              case Right(None) => ()
              case Left(code) => throw new IllegalStateException(s"Exact owned cleanup unavailable: $code instance=$instanceid at $directory")
            }
          }
        }
      } finally {
        _acquired_children.iterator().asScala.foreach { child =>
          _attempt_ {
            if (child.isAlive)
              require(child.stop(), s"Originally acquired child shutdown failed: ${child.instanceId} at $directory")
            require(!child.isAlive, s"Originally acquired child still live: ${child.instanceId} at $directory")
          }
        }
      }
      _attempt_ {
        require(evidence().forall(entry => !nativeAlive(entry)), s"Live case evidence retained at $directory")
      }
      _attempt_ { require(portAvailable, s"Case port $port remains occupied; work retained at $directory") }
      failures.headOption.foreach { primary =>
        failures.tail.foreach(primary.addSuppressed)
        throw primary
      }
      if (_completed) {
        val paths = Files.walk(directory)
        try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete)
        finally paths.close()
      }
    } finally _previous_home match {
      case Some(value) => System.setProperty("user.home", value)
      case None => System.clearProperty("user.home")
    }
  }

  private def _shell(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
  private def _yaml(value: String): String = "'" + value.replace("'", "''") + "'"
}

object CanonicalSupervisorProcessFixture {
  private val _lock = new Object
  private val _prefix = "textus.supervisor.canonical."

  def withFixture[A](run: CanonicalSupervisorProcessFixture => A): A = _lock.synchronized {
    val componentroot = _directory("component-root", "src/main/scala/org/simplemodeling/textus/supervisor/impl/ComponentFactory.scala")
    val runtimeroot = _directory("runtime-root", "src/main/scala/org/goldenport/cncf/CncfMain.scala")
    _regular(componentroot.resolve("project.yaml"))
    _regular(componentroot.resolve("src/main/car/abi-manifest.json"))
    val launcherclasspath = _classpath(_property("launcher-classpath-file"))
    require(launcherclasspath.exists(path => Files.isDirectory(path) && Files.isRegularFile(path.resolve("cncf/launcher/CncfLauncherMain.class"))),
      "Canonical prerequisite: actual compiled Launcher class output is missing")
    val componentclasspath = _classpath(_property("component-classpath-file"))
    val nativeclasspath = _classpath(componentroot.resolve("target/cncf.d/runtime-classpath.txt"))
    _regular(componentroot.resolve("target/cncf.d/car-runtime-manifest.json"))
    _regular(componentroot.resolve("target/cncf.d/component-descriptor.json"))
    _classpath(runtimeroot.resolve("target/cncf.d/runtime-classpath.txt"))
    val marker = "org/simplemodeling/textus/supervisor/impl/ComponentFactory.class"
    val target = componentroot.resolve("target")
    require(componentclasspath.exists(path => path.startsWith(target) && Files.isRegularFile(path.resolve(marker))) &&
      nativeclasspath.exists(path => path.startsWith(target) && Files.isRegularFile(path.resolve(marker))),
      s"Canonical prerequisite: genuine Supervisor own-target $marker missing; run native cozyPrepareRuntime and export Runtime / fullClasspath")
    require(ProcessHandle.current().info().startInstant().isPresent,
      "Canonical prerequisite: native process startInstant is unavailable")
    val base = target.resolve("canonical-process/work")
    Files.createDirectories(base)
    val fixture = new CanonicalSupervisorProcessFixture(componentroot, runtimeroot,
      launcherclasspath.mkString(java.io.File.pathSeparator), Files.createTempDirectory(base, "case-"))
    Using.resource(fixture) { current =>
      val result = run(current)
      current.complete()
      result
    }
  }

  private def _property(name: String): Path = {
    val value = Option(System.getProperty(_prefix + name)).filter(_.trim.nonEmpty)
      .getOrElse(throw new IllegalStateException(s"Canonical prerequisite: missing ${_prefix + name}"))
    val path = Path.of(value)
    require(path.isAbsolute, s"Canonical prerequisite: ${_prefix + name} must be absolute")
    path.normalize
  }

  private def _directory(name: String, marker: String): Path = {
    val path = _property(name)
    require(Files.isDirectory(path), s"Canonical prerequisite: missing checkout directory $path")
    _regular(path.resolve("build.sbt"))
    _regular(path.resolve(marker))
    path
  }

  private def _regular(path: Path): Unit =
    require(Files.isRegularFile(path) && Files.size(path) > 0L,
      s"Canonical prerequisite: missing or empty native prepared input $path")

  private def _classpath(path: Path): Vector[Path] = {
    _regular(path)
    val entries = Files.readAllLines(path).asScala.toVector
      .flatMap(_.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
      .map(_.trim).filter(_.nonEmpty).map(Path.of(_)).distinct
    require(entries.nonEmpty && entries.forall(entry => entry.isAbsolute && Files.exists(entry)),
      s"Canonical prerequisite: classpath must contain existing absolute outputs: $path")
    entries.map(_.normalize)
  }
}
