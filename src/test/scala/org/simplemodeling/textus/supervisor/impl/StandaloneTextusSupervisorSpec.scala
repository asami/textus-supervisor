/*
 * @since   Jul. 28, 2026
 * @version Jul. 28, 2026
 * @author  ASAMI, Tomoharu
 */
package org.simplemodeling.textus.supervisor.impl

import java.nio.file.{Files, Path}
import java.time.Instant

import org.goldenport.configuration.{Configuration, ConfigurationTrace, ConfigurationValue, ResolvedConfiguration}
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.spi.supervisor.{SupervisorAction, SupervisorRequest, SupervisorState}
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class StandaloneTextusSupervisorSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "Standalone Textus Supervisor" should {
    "resolve a validated development launch profile from the selected CAR" in {
      Given("a development CAR descriptor with an authoritative default port")
      val directory = _work_directory("profile")
      Files.writeString(
        directory.resolve("project.yaml"),
        """project:
          |  name: "textus-art-scene"
          |  kind: car
          |  component:
          |    name: "textus-art-scene"
          |    config:
          |      textus.server.default-port: "18011"
          |""".stripMargin
      )

      When("the embedded supervisor resolves the selected target")
      val result = StandaloneDevelopmentLaunchProfile.resolve("textus-art-scene", directory)

      Then("the profile retains only the validated target, directory, and declared port")
      result.map(_.targetId) shouldBe Right("textus-art-scene")
      result.map(_.developmentDirectory) shouldBe Right(directory.toAbsolutePath.normalize)
      result.map(_.defaultPort) shouldBe Right(18011)
    }

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

    "discover standalone development profiles from the Control Center catalog" in {
      Given("an isolated Control Center home and development catalog")
      val home = _work_directory("catalog-home")
      val developmentroot = _work_directory("catalog-root")
      val project = Files.createDirectory(developmentroot.resolve("textus-art-scene"))
      Files.writeString(
        project.resolve("project.yaml"),
        """project:
          |  name: "textus-art-scene"
          |  kind: car
          |  component:
          |    name: "textus-art-scene"
          |    config:
          |      textus.server.default-port: "18011"
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
      val result = supervisor.submit(_request("request-catalog", "catalog-key", SupervisorAction.Start)).toOption.get

      Then("the target starts without launcher supervisor configuration or credentials")
      result.state shouldBe SupervisorState.Accepted
      host.starts shouldBe 1
    }

    "retain completed request evidence across an embedded supervisor restart" in {
      Given("one accepted request persisted by the embedded supervisor")
      val directory = _work_directory("restart")
      val statepath = directory.resolve("supervisor-state.json")
      val host = new RecordingProcessHost
      given ExecutionContext = ExecutionContext.create()
      val first = new StandaloneTextusSupervisor(statepath, host)
      first.registerDevelopmentProfile(StandaloneDevelopmentLaunchProfile("textus-art-scene", directory, 18011))
      val accepted = first.submit(_request("request-retained", "retained-key", SupervisorAction.Start)).toOption.get

      When("Control Center reconstructs its embedded supervisor from the same state")
      val reconstructed = new StandaloneTextusSupervisor(statepath, new RecordingProcessHost)
      reconstructed.registerDevelopmentProfile(StandaloneDevelopmentLaunchProfile("textus-art-scene", directory, 18011))
      val lookedup = reconstructed.lookup("request-retained").toOption.get
      val secondstart = reconstructed.submit(_request("request-second", "second-key", SupervisorAction.Start)).toOption.get

      Then("request evidence survives while stale process ownership remains fail closed")
      lookedup shouldBe Some(accepted)
      secondstart.state shouldBe SupervisorState.Rejected
      secondstart.diagnosticCode shouldBe Some("supervisor-ownership-unavailable")
    }
  }

  private def _request(requestid: String, idempotencykey: String, action: SupervisorAction): SupervisorRequest =
    SupervisorRequest(
      requestid,
      idempotencykey,
      "textus-art-scene",
      Some("standalone"),
      action,
      "operator-1",
      Instant.parse("2030-01-01T00:00:00Z")
    )

  private def _work_directory(label: String): Path = {
    val base = Path.of("target", "test-work")
    Files.createDirectories(base)
    Files.createTempDirectory(base, s"standalone-supervisor-$label-")
  }

  private final class RecordingProcessHost extends StandaloneSupervisorProcessHost {
    var starts = 0
    var children = Vector.empty[RecordingChild]

    def preflight(profile: StandaloneDevelopmentLaunchProfile, owned: Option[StandaloneSupervisorChild]): Either[String, Unit] =
      Right(())

    def start(profile: StandaloneDevelopmentLaunchProfile, instanceId: String): Either[String, StandaloneSupervisorChild] = {
      starts += 1
      val child = new RecordingChild(instanceId, profile.defaultPort)
      children = children :+ child
      Right(child)
    }
  }

  private final class RecordingChild(val instanceId: String, val port: Int) extends StandaloneSupervisorChild {
    var alive = true
    var stops = 0

    def isAlive: Boolean = alive
    def stop(): Boolean = {
      stops += 1
      alive = false
      true
    }
  }
}
