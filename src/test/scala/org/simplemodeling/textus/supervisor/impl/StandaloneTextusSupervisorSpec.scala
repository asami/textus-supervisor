/*
 * @since   Jul. 28, 2026
 *  version Jul. 28, 2026
 * @version Aug. 10, 2026
 * @author  ASAMI, Tomoharu
 */
package org.simplemodeling.textus.supervisor.impl

import java.io.File
import java.net.ServerSocket
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.concurrent.TimeUnit

import org.goldenport.configuration.{Configuration, ConfigurationTrace, ConfigurationValue, ResolvedConfiguration}
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.spi.supervisor.{SupervisorAction, SupervisorRequest, SupervisorState}
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class StandaloneTextusSupervisorSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "Standalone Textus Supervisor" should {
    "parse launch profiles and discover catalog sources" which {
      "resolve a validated development launch profile from the selected CAR" in {
      Given("a development CAR descriptor with an authoritative default port")
      val directory = _work_directory("profile")
      Files.writeString(
        directory.resolve("project.yaml"),
        """project:
          |  namespace: "org.simplemodeling.textus"
          |  id: "ArtScene"
          |  kind: car
          |  component:
          |    config:
          |      textus.server.default-port: "18011"
          |""".stripMargin
      )

      When("the embedded supervisor resolves the selected target")
      val result = StandaloneDevelopmentLaunchProfile.resolve("textus-art-scene", directory)

      Then("the profile retains only the validated target, directory, and declared port")
      result.map(_.targetId) shouldBe Right("textus-art-scene")
      result.map(_.developmentDirectory) shouldBe Right(directory.toAbsolutePath.normalize)
      result.map(_.defaultPort) shouldBe Right(Some(18011))
    }

      "discover a canonical ArtScene development launch profile" in {
      Given("a canonical ArtScene CAR descriptor without a legacy project name")
      val directory = _work_directory("discover-profile")
      Files.writeString(
        directory.resolve("project.yaml"),
        """project:
          |  namespace: "org.simplemodeling.textus"
          |  id: "ArtScene"
          |  kind: car
          |  component:
          |    config:
          |      textus.server.default-port: "18011"
          |""".stripMargin
      )

      When("the standalone provider discovers the development directory")
      val profile = StandaloneDevelopmentLaunchProfile.discover(directory)

      Then("the projected Maven artifact ID is retained as the target")
      profile.map(_.targetId) shouldBe Some("textus-art-scene")
      profile.map(_.defaultPort) shouldBe Some(Some(18011))
    }

      "discover standalone development profiles from the Control Center catalog" in {
      Given("an isolated Control Center home and development catalog")
      val home = _work_directory("catalog-home")
      val developmentroot = _work_directory("catalog-root")
      val project = Files.createDirectory(developmentroot.resolve("textus-art-scene"))
      Files.writeString(
        project.resolve("project.yaml"),
        """project:
          |  namespace: "org.simplemodeling.textus"
          |  id: "ArtScene"
          |  kind: car
          |  component:
          |    config:
          |      textus.server.default-port: "18011"
          |""".stripMargin
      )
      val assemblydirectory = Files.createDirectories(project.resolve("src/main/car"))
      Files.writeString(
        assemblydirectory.resolve("assembly-descriptor.yaml"),
        """components:
          |  - namespace: org.simplemodeling.textus
          |    id: ArtScene
          |  - namespace: org.simplemodeling.textus
          |    id: AiRuntime
          |""".stripMargin
      )
      val airuntime = Files.createDirectory(developmentroot.resolve("textus-ai"))
      Files.writeString(
        airuntime.resolve("project.yaml"),
        """project:
          |  namespace: "org.simplemodeling.textus"
          |  id: "AiRuntime"
          |  kind: car
          |  component:
          |    config:
          |      textus.server.default-port: "18012"
          |""".stripMargin
      )
      val aws = Files.createDirectory(developmentroot.resolve("textus-aws"))
      Files.writeString(
        aws.resolve("project.yaml"),
        """project:
          |  namespace: "org.simplemodeling.textus"
          |  id: "Aws"
          |  kind: car
          |  component:
          |    config:
          |      textus.server.default-port: "18013"
          |""".stripMargin
      )
      Files.writeString(
        home.resolve("catalog.yaml"),
        s"""schema: textus-control-center.catalog.v1
           |development:
           |  roots:
           |    - id: test
           |      path: ${developmentroot.toAbsolutePath.normalize}
           |      include-prefix: textus-
           |""".stripMargin
      )
      val host = new RecordingProcessHost
      val supervisor = new StandaloneTextusSupervisor(home.resolve("state.json"), host)
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          StandaloneDevelopmentProfileRegistry.CONTROL_CENTER_HOME_KEY ->
            ConfigurationValue.StringValue(home.toString)
        )),
        ConfigurationTrace.empty
      )
      given ExecutionContext = ExecutionContext.create()

      When("the embedded provider registers profiles from the shared standalone installation")
      StandaloneDevelopmentProfileRegistry.registerConfigured(supervisor, configuration)
      val result = supervisor.submit(_request("request-catalog", "catalog-key", SupervisorAction.Start, Some("DEV:test"))).toOption.get

      Then("the target starts without launcher supervisor configuration or credentials")
      result.state shouldBe SupervisorState.Accepted
      host.starts shouldBe 1
      host.profiles.head.asInstanceOf[StandaloneDevelopmentLaunchProfile].developmentRepositoryDirectories shouldBe
        Vector(airuntime, project).map(_.toAbsolutePath.normalize).sortBy(_.toString)
      StandaloneSupervisorProcessHost.System.command(host.profiles.head, "instance-1") shouldBe Vector(
        "cncf",
        "server",
        "--textus.server.port=18011",
        "--textus.control-center.registration-instance-id=instance-1",
        "--textus.repository.component.dev.dir=" + Vector(airuntime, project).map(_.toAbsolutePath.normalize).sortBy(_.toString).mkString(",")
      )
    }

    }

    "manage process readiness and ownership cleanup" which {
      "persist a failed startup readiness result without retaining ownership" in {
        Given("a child that remains alive but does not become ready during startup")
        val directory = _work_directory("startup-readiness-failure")
        val statepath = directory.resolve("supervisor-state.json")
        val host = new StartupReadinessProcessHost
        val supervisor = new StandaloneTextusSupervisor(statepath, host)
        supervisor.registerDevelopmentProfile(StandaloneDevelopmentLaunchProfile("textus-art-scene", directory, 18011))
        given ExecutionContext = ExecutionContext.create()
        val failedrequest = _request("request-startup-readiness-failed", "startup-readiness-failed-key", SupervisorAction.Start)

        When("the operator starts the component and then retries after readiness failure")
        val failed = supervisor.submit(failedrequest).toOption.get
        val retained = supervisor.lookup(failedrequest.requestId).toOption.get
        val retried = supervisor.submit(_request("request-startup-readiness-retry", "startup-readiness-retry-key", SupervisorAction.Start)).toOption.get

        Then("Control Center can retain the failed request while the released ownership permits a later start")
        failed.state shouldBe SupervisorState.Failed
        failed.diagnosticCode shouldBe Some(StandaloneSupervisorProcessHost.EXECUTION_UNAVAILABLE)
        failed.instanceId should not be empty
        retained shouldBe Some(failed)
        retried.state shouldBe SupervisorState.Accepted
        host.starts shouldBe 2
        host.children.head.stops shouldBe 1
      }

      "use the canonical server command in the selected development directory" in {
        Given("a validated ArtScene development profile")
        val directory = _work_directory("canonical-command")
        val profile = StandaloneDevelopmentLaunchProfile("textus-art-scene", directory, 18011)

        When("the standalone process command is prepared")
        val command = StandaloneSupervisorProcessHost.System.command(profile, "instance-1")

        Then("the public cncf server form is preserved without a launcher supervisor command")
        command shouldBe Vector(
          "cncf",
          "server",
          "--textus.server.port=18011",
          "--textus.control-center.registration-instance-id=instance-1"
        )
        command should not contain directory.toString
      }

      "remove launcher-private environment entries while preserving unrelated child settings" in {
        Given("a child environment containing launcher-private and unrelated entries")
        val environment = new java.util.HashMap[String, String]()
        environment.put("CNCF_LAUNCHER_ARGS_FILE", "/var/tmp/launcher-args.bin")
        environment.put("CNCF_LAUNCHER_DEV_DELEGATED", "1")
        environment.put("UNRELATED_ENV", "retained")

        When("the standalone process host sanitizes the child environment")
        StandaloneSupervisorProcessHost.System._sanitize_environment(environment)

        Then("only launcher-private entries are removed")
        environment.containsKey("CNCF_LAUNCHER_ARGS_FILE") shouldBe false
        environment.containsKey("CNCF_LAUNCHER_DEV_DELEGATED") shouldBe false
        environment.get("UNRELATED_ENV") shouldBe "retained"
      }

      "stop a launcher wrapper together with its captured long-lived descendant" in {
        Given("a Java launcher wrapper that leaves a long-lived server descendant")
        val port = _free_port()
        val process = _process_tree_fixture("wrapper", port)
        val child = StandaloneSupervisorProcessHost.System._child(process, "process-tree-fixture", Some(port))

        try {
          When("the wrapper exits after its descendant becomes ready")
          child.awaitReadiness() shouldBe true
          _await_condition(5000L)(!process.isAlive) shouldBe true

          Then("the supervisor-owned process tree remains observable and stops together")
          child.isAlive shouldBe true
          child.stop() shouldBe true
          child.isAlive shouldBe false
        } finally {
          child.stop()
          if (process.isAlive) {
            process.destroyForcibly()
            try process.waitFor(5, TimeUnit.SECONDS)
            catch {
              case _: InterruptedException => Thread.currentThread.interrupt()
            }
          }
        }
      }
    }

    "own lifecycle, source selection, and recovery" which {
      "own start and stop without a launcher-private authority or credential" in {
      Given("an embedded supervisor with one registered development profile")
      val directory = _work_directory("lifecycle")
      val statepath = directory.resolve("supervisor-state.json")
      val host = new RecordingProcessHost
      val supervisor = new StandaloneTextusSupervisor(statepath, host)
      supervisor.registerDevelopmentProfile(StandaloneDevelopmentLaunchProfile("textus-art-scene", directory, 18011))
      given ExecutionContext = ExecutionContext.create()
      val start = _request("request-start", "start-key", SupervisorAction.Start)

      When("the operator starts and then stops the component")
      val started = supervisor.submit(start).toOption.get
      val repeated = supervisor.submit(start).toOption.get
      val altered = supervisor.submit(start.copy(action = SupervisorAction.Stop)).toOption.get
      val stopped = supervisor.submit(_request("request-stop", "stop-key", SupervisorAction.Stop)).toOption.get

      Then("the embedded supervisor owns one child and keeps the request idempotent")
      started.state shouldBe SupervisorState.Accepted
      started.supervisorId shouldBe StandaloneTextusSupervisor.SUPERVISOR_ID
      started.instanceId should not be empty
      repeated shouldBe started
      altered shouldBe started
      stopped.state shouldBe SupervisorState.Stopped
      stopped.instanceId shouldBe started.instanceId
      host.starts shouldBe 1
      host.children.head.stops shouldBe 1
    }

      "reject a start without an explicit deployment source" in {
      Given("an embedded supervisor with a registered standalone development profile")
      val directory = _work_directory("missing-start-source")
      val host = new RecordingProcessHost
      val supervisor = new StandaloneTextusSupervisor(directory.resolve("state.json"), host)
      supervisor.registerDevelopmentProfile(StandaloneDevelopmentLaunchProfile("textus-art-scene", directory, 18011))
      given ExecutionContext = ExecutionContext.create()

      When("the operator starts the component without a deployment ID")
      val result = supervisor.submit(_request("request-missing-start-source", "missing-start-source-key", SupervisorAction.Start, None)).toOption.get

      Then("the request is rejected without falling back to the standalone profile")
      result.state shouldBe SupervisorState.Rejected
      result.diagnosticCode shouldBe Some(StandaloneDevelopmentLaunchProfile.PROFILE_UNAVAILABLE)
      host.starts shouldBe 0
    }

      "stop retained ownership without requiring a deployment source" in {
      Given("a child started through one explicit development source")
      val directory = _work_directory("artifact-only-stop")
      val host = new RecordingProcessHost
      val supervisor = new StandaloneTextusSupervisor(directory.resolve("state.json"), host)
      supervisor.registerDevelopmentProfile(StandaloneDevelopmentLaunchProfile("textus-art-scene", directory, 18011, "DEV:development-source"))
      given ExecutionContext = ExecutionContext.create()

      When("the operator stops it without a deployment ID")
      val started = supervisor.submit(_request("request-sourced-start", "sourced-start", SupervisorAction.Start, Some("DEV:development-source"))).toOption.get
      val stopped = supervisor.submit(_request("request-artifact-stop", "artifact-stop", SupervisorAction.Stop, None)).toOption.get

      Then("the retained child is stopped while source selection remains required for starts")
      started.state shouldBe SupervisorState.Accepted
      stopped.state shouldBe SupervisorState.Stopped
      stopped.instanceId shouldBe started.instanceId
    }

      "select the exact development, local, or public source without source fallback" in {
      Given("a Control Center catalog with one development, local, and public source")
      val home = _work_directory("source-profiles-home")
      val developmentroot = _work_directory("source-profiles-development")
      val project = Files.createDirectory(developmentroot.resolve("textus-art-scene"))
      Files.writeString(
        project.resolve("project.yaml"),
        """project:
          |  namespace: "org.simplemodeling.textus"
          |  id: "ArtScene"
          |  kind: car
          |  component:
          |    config:
          |      textus.server.default-port: "18011"
          |""".stripMargin
      )
      val localcatalogroot = home.resolve("repository/catalog/car")
      Files.createDirectories(localcatalogroot)
      Files.writeString(
        home.resolve("catalog.yaml"),
        s"""schema: textus-control-center.catalog.v1
           |development:
           |  roots:
           |    - id: development-source
           |      path: ${developmentroot.toAbsolutePath.normalize}
           |      include-prefix: textus-
           |local-repository:
           |  id: local-source
           |  catalog-root: ${localcatalogroot.toAbsolutePath.normalize}
           |public-repositories:
           |  - id: public-source
           |    catalog-base-url: https://example.com/repository/catalog/car
           |    subscriptions:
           |      - textus-art-scene
           |""".stripMargin
      )
      val host = new RecordingProcessHost
      val supervisor = new StandaloneTextusSupervisor(home.resolve("state.json"), host)
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          StandaloneDevelopmentProfileRegistry.CONTROL_CENTER_HOME_KEY ->
            ConfigurationValue.StringValue(home.toString)
        )),
        ConfigurationTrace.empty
      )
      given ExecutionContext = ExecutionContext.create()

      When("Control Center requests each declared deployment source")
      StandaloneDevelopmentProfileRegistry.registerConfigured(supervisor, configuration)
      val development = supervisor.submit(_request("request-development", "development-key", SupervisorAction.Start, Some("DEV:development-source"))).toOption.get
      host.children.head.alive = false
      val local = supervisor.submit(_request("request-local", "local-key", SupervisorAction.Start, Some("LOCAL:local-source"))).toOption.get
      host.children.last.alive = false
      val public = supervisor.submit(_request("request-public", "public-key", SupervisorAction.Start, Some("PUBLIC:public-source"))).toOption.get
      val unavailable = supervisor.submit(_request("request-unavailable", "unavailable-key", SupervisorAction.Start, Some("DEV:missing-source"))).toOption.get

      Then("each source has its exact profile and an unknown source cannot fall back")
      development.state shouldBe SupervisorState.Accepted
      local.state shouldBe SupervisorState.Accepted
      public.state shouldBe SupervisorState.Accepted
      unavailable.state shouldBe SupervisorState.Rejected
      unavailable.diagnosticCode shouldBe Some(StandaloneDevelopmentLaunchProfile.PROFILE_UNAVAILABLE)
      host.profiles.map(_.deploymentId) shouldBe Vector("DEV:development-source", "LOCAL:local-source", "PUBLIC:public-source")
      host.profiles.map(StandaloneSupervisorProcessHost.System.command(_, "instance-1")) shouldBe Vector(
        Vector(
          "cncf",
          "server",
          "--textus.server.port=18011",
          "--textus.control-center.registration-instance-id=instance-1",
          "--textus.repository.component.dev.dir=" + developmentroot.toAbsolutePath.normalize.resolve("textus-art-scene")
        ),
        Vector("cncf", "--name", "textus-art-scene", "--artifact-repository", home.resolve("repository/car").toAbsolutePath.normalize.toString, "server", "--textus.control-center.registration-instance-id=instance-1"),
        Vector("cncf", "--name", "textus-art-scene", "--artifact-repository", "https://example.com/repository/car", "server", "--textus.control-center.registration-instance-id=instance-1")
      )
    }

      "release proven dead child ownership before a later start" in {
      Given("a standalone supervisor whose owned child has exited")
      val directory = _work_directory("dead-child")
      val host = new RecordingProcessHost
      val supervisor = new StandaloneTextusSupervisor(directory.resolve("state.json"), host)
      supervisor.registerDevelopmentProfile(StandaloneDevelopmentLaunchProfile("textus-art-scene", directory, 18011))
      given ExecutionContext = ExecutionContext.create()
      val first = supervisor.submit(_request("request-first", "first-key", SupervisorAction.Start)).toOption.get
      host.children.head.alive = false

      When("the operator submits a new start while the same runtime retains the dead child handle")
      val second = supervisor.submit(_request("request-second", "second-key", SupervisorAction.Start)).toOption.get

      Then("the proven dead ownership is replaced without discovering an unrelated process")
      first.state shouldBe SupervisorState.Accepted
      second.state shouldBe SupervisorState.Accepted
      second.instanceId should not be first.instanceId
      host.starts shouldBe 2
    }

      "keep an idempotency key scoped to its matching durable request ID" in {
      Given("one accepted start request and a later request that reuses its idempotency key")
      val directory = _work_directory("idempotency-request-id")
      val host = new RecordingProcessHost
      val supervisor = new StandaloneTextusSupervisor(directory.resolve("state.json"), host)
      supervisor.registerDevelopmentProfile(StandaloneDevelopmentLaunchProfile("textus-art-scene", directory, 18011))
      given ExecutionContext = ExecutionContext.create()
      val first = supervisor.submit(_request("request-first", "shared-key", SupervisorAction.Start)).toOption.get

      When("a distinct request ID is submitted with the same key")
      val second = supervisor.submit(_request("request-second", "shared-key", SupervisorAction.Start)).toOption.get

      Then("the supervisor does not return a result bearing the previous request ID")
      first.state shouldBe SupervisorState.Accepted
      second.requestId shouldBe "request-second"
      second.state shouldBe SupervisorState.Rejected
      host.starts shouldBe 1
    }

      "recover stale development ownership across an embedded supervisor restart" in {
      Given("one accepted development request persisted by the embedded supervisor")
      val directory = _work_directory("restart")
      val statepath = directory.resolve("supervisor-state.json")
      val host = new RecordingProcessHost
      given ExecutionContext = ExecutionContext.create()
      val first = new StandaloneTextusSupervisor(statepath, host)
      first.registerDevelopmentProfile(StandaloneDevelopmentLaunchProfile("textus-art-scene", directory, 18011, "DEV:dev2026"))
      val accepted = first.submit(_request("request-retained", "retained-key", SupervisorAction.Start, Some("DEV:dev2026"))).toOption.get

      When("Control Center reconstructs its embedded supervisor from the same state")
      val reconstructedhost = new RecordingProcessHost
      val reconstructed = new StandaloneTextusSupervisor(statepath, reconstructedhost)
      reconstructed.registerDevelopmentProfile(StandaloneDevelopmentLaunchProfile("textus-art-scene", directory, 18011, "DEV:dev2026"))
      val lookedup = reconstructed.lookup("request-retained").toOption.get
      val secondstart = reconstructed.submit(_request("request-second", "second-key", SupervisorAction.Start, Some("DEV:dev2026"))).toOption.get

      Then("request evidence survives and a free declared port recovers stale development ownership")
      lookedup shouldBe Some(accepted)
      secondstart.state shouldBe SupervisorState.Accepted
      reconstructedhost.preflights shouldBe 1
      reconstructedhost.starts shouldBe 1
    }

      "retain stale development ownership when preflight rejects recovery" in {
      Given("one accepted development request whose persisted ownership has no reconstructed child handle")
      val directory = _work_directory("restart-preflight-rejected")
      val statepath = directory.resolve("supervisor-state.json")
      given ExecutionContext = ExecutionContext.create()
      val first = new StandaloneTextusSupervisor(statepath, new RecordingProcessHost)
      first.registerDevelopmentProfile(StandaloneDevelopmentLaunchProfile("textus-art-scene", directory, 18011, "DEV:dev2026"))
      val accepted = first.submit(_request("request-preflight-retained", "preflight-retained-key", SupervisorAction.Start, Some("DEV:dev2026"))).toOption.get

      When("the selected development profile reports its declared port unavailable")
      val reconstructed = new StandaloneTextusSupervisor(statepath, new UnavailablePreflightProcessHost)
      reconstructed.registerDevelopmentProfile(StandaloneDevelopmentLaunchProfile("textus-art-scene", directory, 18011, "DEV:dev2026"))
      val rejected = reconstructed.submit(_request("request-preflight-second", "preflight-second-key", SupervisorAction.Start, Some("DEV:dev2026"))).toOption.get

      Then("the exact preflight code and retained ownership instance identify the rejected recovery")
      rejected.state shouldBe SupervisorState.Rejected
      rejected.diagnosticCode shouldBe Some(StandaloneSupervisorProcessHost.PORT_UNAVAILABLE)
      rejected.instanceId shouldBe accepted.instanceId
    }

      "keep stale repository ownership fail closed across an embedded supervisor restart" in {
      Given("one accepted repository request persisted by the embedded supervisor")
      val directory = _work_directory("repository-restart")
      val statepath = directory.resolve("supervisor-state.json")
      given ExecutionContext = ExecutionContext.create()
      val first = new StandaloneTextusSupervisor(statepath, new RecordingProcessHost)
      first.registerRepositoryProfile(StandaloneRepositoryLaunchProfile("LOCAL:repository", directory.toString))
      val accepted = first.submit(_request("request-repository-retained", "repository-retained-key", SupervisorAction.Start, Some("LOCAL:repository"))).toOption.get

      When("Control Center reconstructs its embedded supervisor without the child handle")
      val reconstructedhost = new RecordingProcessHost
      val reconstructed = new StandaloneTextusSupervisor(statepath, reconstructedhost)
      reconstructed.registerRepositoryProfile(StandaloneRepositoryLaunchProfile("LOCAL:repository", directory.toString))
      val secondstart = reconstructed.submit(_request("request-repository-second", "repository-second-key", SupervisorAction.Start, Some("LOCAL:repository"))).toOption.get

      Then("repository ownership remains fail closed because its runtime cannot be observed by a declared port")
      accepted.state shouldBe SupervisorState.Accepted
      secondstart.state shouldBe SupervisorState.Rejected
      secondstart.diagnosticCode shouldBe Some("supervisor-ownership-unavailable")
      reconstructedhost.starts shouldBe 0
    }
    }
  }

  private def _request(
    requestid: String,
    idempotencykey: String,
    action: SupervisorAction,
    deploymentid: Option[String] = Some("standalone")
  ): SupervisorRequest =
    SupervisorRequest(
      requestid,
      idempotencykey,
      "textus-art-scene",
      deploymentid,
      action,
      "operator-1",
      Instant.parse("2030-01-01T00:00:00Z")
    )

  private def _work_directory(label: String): Path = {
    val base = Path.of("target", "test-work")
    Files.createDirectories(base)
    Files.createTempDirectory(base, s"standalone-supervisor-$label-")
  }

  private def _free_port(): Int = {
    val socket = new ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()
  }

  private def _process_tree_fixture(mode: String, port: Int): Process = {
    val javaexecutable = Path.of(java.lang.System.getProperty("java.home"), "bin", "java").toString
    val classpath = StandaloneSupervisorProcessTreeFixture.fixtureClasspath
    new ProcessBuilder(
      javaexecutable,
      "-cp",
      classpath,
      "org.simplemodeling.textus.supervisor.impl.StandaloneSupervisorProcessTreeFixture",
      mode,
      port.toString
    ).redirectError(ProcessBuilder.Redirect.INHERIT).redirectOutput(ProcessBuilder.Redirect.INHERIT).start()
  }

  private def _await_condition(timeoutmillis: Long)(condition: => Boolean): Boolean = {
    val deadline = java.lang.System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutmillis)
    var satisfied = condition
    var interrupted = false
    while (!satisfied && !interrupted && java.lang.System.nanoTime() < deadline) {
      try Thread.sleep(20L)
      catch {
        case _: InterruptedException =>
          Thread.currentThread.interrupt()
          interrupted = true
      }
      satisfied = condition
    }
    satisfied
  }

  private final class RecordingProcessHost extends StandaloneSupervisorProcessHost {
    var preflights = 0
    var starts = 0
    var children = Vector.empty[RecordingChild]
    var profiles = Vector.empty[StandaloneLaunchProfile]

    def preflight(profile: StandaloneLaunchProfile, owned: Option[StandaloneSupervisorChild]): Either[String, Unit] = {
      preflights += 1
      Right(())
    }

    def start(profile: StandaloneLaunchProfile, instanceId: String): Either[String, StandaloneSupervisorChild] = {
      starts += 1
      profiles = profiles :+ profile
      val child = new RecordingChild(instanceId, profile.defaultPort)
      children = children :+ child
      Right(child)
    }
  }

  private final class RecordingChild(
    val instanceId: String,
    val port: Option[Int],
    startupready: Boolean = true
  ) extends StandaloneSupervisorChild {
    var alive = true
    var stops = 0

    def isAlive: Boolean = alive
    override def awaitReadiness(): Boolean = startupready
    def stop(): Boolean = {
      stops += 1
      alive = false
      true
    }
  }

  private final class StartupReadinessProcessHost extends StandaloneSupervisorProcessHost {
    var starts = 0
    var children = Vector.empty[RecordingChild]

    def preflight(profile: StandaloneLaunchProfile, owned: Option[StandaloneSupervisorChild]): Either[String, Unit] =
      Right(())

    def start(profile: StandaloneLaunchProfile, instanceId: String): Either[String, StandaloneSupervisorChild] = {
      starts += 1
      val child = new RecordingChild(instanceId, profile.defaultPort, starts > 1)
      children = children :+ child
      Right(child)
    }
  }

  private final class UnavailablePreflightProcessHost extends StandaloneSupervisorProcessHost {
    def preflight(profile: StandaloneLaunchProfile, owned: Option[StandaloneSupervisorChild]): Either[String, Unit] =
      Left(StandaloneSupervisorProcessHost.PORT_UNAVAILABLE)

    def start(profile: StandaloneLaunchProfile, instanceId: String): Either[String, StandaloneSupervisorChild] =
      Left(StandaloneSupervisorProcessHost.EXECUTION_UNAVAILABLE)
  }
}

object StandaloneSupervisorProcessTreeFixture {
  def fixtureClasspath: String =
    Vector(
      StandaloneSupervisorProcessTreeFixture.getClass,
      classOf[scala.deriving.Mirror],
      classOf[scala.Option[?]]
    ).map(_.getProtectionDomain.getCodeSource.getLocation.toURI).map(uri => Path.of(uri).toString).distinct.mkString(File.pathSeparator)

  def main(args: Array[String]): Unit = args.toVector match {
    case Vector("wrapper", port) =>
      _start_descendant(port)
      try Thread.sleep(500L)
      catch {
        case _: InterruptedException => Thread.currentThread.interrupt()
      }
    case Vector("descendant", port) =>
      val socket = new ServerSocket(port.toInt)
      var running = true
      try {
        while (running) {
          try Thread.sleep(1000L)
          catch {
            case _: InterruptedException =>
              Thread.currentThread.interrupt()
              running = false
          }
        }
      } finally socket.close()
    case _ => ()
  }

  private def _start_descendant(port: String): Process = {
    val javaexecutable = Path.of(java.lang.System.getProperty("java.home"), "bin", "java").toString
    val classpath = StandaloneSupervisorProcessTreeFixture.fixtureClasspath
    new ProcessBuilder(
      javaexecutable,
      "-cp",
      classpath,
      "org.simplemodeling.textus.supervisor.impl.StandaloneSupervisorProcessTreeFixture",
      "descendant",
      port
    ).redirectError(ProcessBuilder.Redirect.INHERIT).redirectOutput(ProcessBuilder.Redirect.INHERIT).start()
  }
}
