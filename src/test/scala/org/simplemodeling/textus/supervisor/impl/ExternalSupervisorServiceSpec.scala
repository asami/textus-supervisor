/*
 * @since   Oct. 16, 2026
 * @version Oct. 16, 2026
 */
package org.simplemodeling.textus.supervisor.impl

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import io.circe.Json
import io.circe.parser.parse
import org.goldenport.configuration.{Configuration, ConfigurationTrace, ConfigurationValue, ResolvedConfiguration}
import org.goldenport.cncf.component.Component
import org.goldenport.cncf.context.{Capability, ExecutionContext, SecurityContext, SubjectKind}
import org.goldenport.cncf.http.{SupervisorHttpRouteFixture, UrlConnectionHttpDriver}
import org.goldenport.cncf.security.SecuritySubject
import org.goldenport.cncf.spi.{SpiContract, SpiSelection}
import org.goldenport.cncf.spi.supervisor.{Supervisor, SupervisorAction, SupervisorRequest, SupervisorState}
import org.goldenport.cncf.subsystem.{GenericSubsystemAuthenticationBinding, GenericSubsystemAuthenticationProviderBinding, GenericSubsystemComponentBinding, GenericSubsystemDescriptor, GenericSubsystemSecurityBinding, SubsystemExecutionProfile}
import org.goldenport.cncf.unitofwork.UnitOfWorkOp
import org.goldenport.http.HttpResponse
import org.goldenport.protocol.Property
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Actual generated Service ingress and durable backend; only OS child ownership is recorded. */
final class ExternalSupervisorServiceSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  private val _service_token = "protected-supervisor-service-secret"
  private val _principal_id = "control-center-service-spec"
  private val _query = "textus.format=json&textus.output.shape=data&textus.command.execution-mode=sync"

  "External generated Supervisor Service" should {
    "authenticate normal ingress (XP2)" which {
      "denies absent, incorrect and Launcher credentials before either Operation can expose durable evidence" in {
        Given("an authenticated-user runtime with a real Service authenticator and a retained lifecycle request")
        _with_fixture { fixture =>
          SupervisorHttpRouteFixture.withServer(fixture.subsystem) { endpoint =>
            given ExecutionContext = SupervisorServiceOperationFixture.context(httpDriver = Some(new UrlConnectionHttpDriver("")))
            val request = SupervisorServiceOperationFixture.request("protected-retained")
            val retained = _proxy(endpoint).submit(request).TAKE
            val before = (fixture.backend.requests.size, fixture.backend.lookups.size)
            val forged = parse(SupervisorOperationJson.encode(request.copy(requestId = "forged", operatorSubjectId = "system")))
              .toOption.get.mapObject(_.add("privilege", Json.fromString("system"))
                .add("authenticated", Json.True).add("principal_id", Json.fromString(_principal_id))).noSpaces

            When("both generated Operations receive absent, wrong or Launcher tokens plus forged authority claims")
            val responses = Vector(None, Some("wrong-token"), Some("launcher-registration-secret")).flatMap { token =>
              Vector(
                _raw(endpoint, "submit", Some(forged), token),
                _raw(endpoint, "lookup", None, token, request.requestId)
              )
            }

            Then("normal ingress rejects every attempt without reaching the shared Service backend")
            responses.foreach { response =>
              Set(401, 403) should contain(response.code)
              val text = response.getString.getOrElse("")
              retained.instanceId.foreach(instance => text should not include instance)
              text should not include _service_token
              text should not include "launcher-registration-secret"
            }
            (fixture.backend.requests.size, fixture.backend.lookups.size) shouldBe before
            fixture.host.starts shouldBe 1
            fixture.subsystem.executionProfileC.TAKE shouldBe SubsystemExecutionProfile.Authenticated
            fixture.subsystem.resolvedSecurityWiring.authentication.fallbackPrivilegeEnabled shouldBe false
          }
        }
      }
    }

    "preserve lifecycle semantics over HTTP (XP4)" which {
      "uses actual generated submit and lookup with one durable child start and a separate represented operator" in {
        Given("the actual CNCF HTTP routes, generated Operations, shared Service and standalone durable backend")
        _with_fixture { fixture =>
          SupervisorHttpRouteFixture.withServer(fixture.subsystem) { endpoint =>
            given ExecutionContext = SupervisorServiceOperationFixture.context(httpDriver = Some(new UrlConnectionHttpDriver("")))
            val request = SupervisorServiceOperationFixture.request("actual-http +/&?=é")
            val consumerhome = fixture.directory.resolve("external-consumer-home")
            val proxy = _proxy(endpoint, Some(consumerhome.toString))

            When("the installed external SPI submits, retries, looks up and is reconstructed as a new consumer")
            val submitted = proxy.submit(request).TAKE
            val retry = proxy.submit(request).TAKE
            val lookedup = proxy.lookup(request.requestId).TAKE
            val unknown = proxy.lookup("unknown +/&?=é").TAKE
            val reconstructed = _proxy(endpoint, Some(consumerhome.toString)).lookup(request.requestId).TAKE

            Then("typed durable results and instance correlation survive with exactly one child start")
            submitted.state shouldBe SupervisorState.Accepted
            submitted.requestId shouldBe request.requestId
            submitted.instanceId should not be empty
            submitted.acceptedAt should not be empty
            retry shouldBe submitted
            lookedup shouldBe Some(submitted)
            reconstructed shouldBe Some(submitted)
            unknown shouldBe None
            fixture.host.starts shouldBe 1
            Files.isRegularFile(fixture.statePath) shouldBe true
            Files.exists(consumerhome) shouldBe false
            fixture.backend.requests shouldBe Vector(request, request)
            fixture.backend.lookups shouldBe Vector(request.requestId, "unknown +/&?=é", request.requestId)
            fixture.backend.contexts should have size 5
            fixture.backend.contexts.foreach { context =>
              val subject = SecuritySubject.current(using context)
              subject.isAuthenticated shouldBe true
              context.security.subjectKind shouldBe SubjectKind.Service
              context.security.principal.id.value shouldBe _principal_id
              context.security.principal.id.value should not be request.operatorSubjectId
              context.security.capabilities shouldBe Set(Capability(SecurityContext.Privilege.Operator.name))
              context.security.principal.attributes.values.foreach(_ should not include _service_token)
            }
            fixture.backend.requests.foreach { captured =>
              captured.operatorSubjectId shouldBe "represented-operator"
              val json = parse(SupervisorOperationJson.encode(captured)).toOption.get
              json.asObject.get.keys.toSet shouldBe Set("requestId", "idempotencyKey", "targetId", "deploymentId", "action", "operatorSubjectId", "deadlineAt")
              json.noSpaces should not include _service_token
              json.noSpaces should not include fixture.directory.toString
            }
          }
        }
      }
    }

    "retain evidence across reconstruction (XP5)" which {
      "replays durable results while refusing unproven ownership without signalling recorded children" in {
        Given("one remote durable state file and a process host that cannot prove recovered ownership")
        _with_fixture { fixture =>
          given ExecutionContext = SupervisorServiceOperationFixture.context(httpDriver = Some(new UrlConnectionHttpDriver("")))
          val request = SupervisorServiceOperationFixture.request("remote-before-restart")

          When("a request is submitted and looked up through a reconstructed consumer")
          val initial = SupervisorHttpRouteFixture.withServer(fixture.subsystem) { endpoint =>
            val result = _proxy(endpoint).submit(request).TAKE
            Then("the initial lookup returns the retained submitted result")
            _proxy(endpoint).lookup(request.requestId).TAKE shouldBe Some(result)
            result
          }
          When("the remote backend is reconstructed and receives replay, lookup and ownership-sensitive requests")
          val reconstructed = fixture.reconstruct()
          _wire_fixture(reconstructed)
          val evidence = SupervisorHttpRouteFixture.withServer(reconstructed.subsystem) { endpoint =>
            val proxy = _proxy(endpoint)
            val replay = proxy.submit(request).TAKE
            val lookup = proxy.lookup(request.requestId).TAKE
            val stop = proxy.submit(SupervisorServiceOperationFixture.request("after-restart-stop", SupervisorAction.Stop)).TAKE
            val restart = proxy.submit(SupervisorServiceOperationFixture.request("after-restart-restart", SupervisorAction.Restart)).TAKE
            (replay, lookup, stop, restart)
          }

          Then("the previous result survives and new ownership-sensitive work fails closed without a duplicate start")
          evidence._1 shouldBe initial
          evidence._2 shouldBe Some(initial)
          evidence._3.state shouldBe SupervisorState.Rejected
          evidence._4.state shouldBe SupervisorState.Rejected
          evidence._3.diagnosticCode shouldBe Some(StandaloneSupervisorProcessHost.RECOVERY_UNAVAILABLE)
          evidence._4.diagnosticCode shouldBe Some(StandaloneSupervisorProcessHost.RECOVERY_UNAVAILABLE)
          fixture.host.starts shouldBe 1
          reconstructed.host.starts shouldBe 0
          reconstructed.host.recoveries should be >= 1
          reconstructed.host.children shouldBe empty
          fixture.host.children.map(_.stops) shouldBe Vector(0)
          reconstructed.backend.requests.head shouldBe request
          reconstructed.backend.contexts.foreach(_.security.subjectKind shouldBe SubjectKind.Service)
        }
      }
    }
  }

  private def _configuration(entries: (String, String)*): ResolvedConfiguration =
    ResolvedConfiguration(Configuration(entries.map { case (key, value) =>
      key -> ConfigurationValue.StringValue(value)
    }.toMap), ConfigurationTrace.empty)

  private def _with_fixture[A](run: SupervisorServiceOperationFixture => A): A = {
    val configuration = _configuration(
      "textus.subsystem.user-mode" -> "multi-user",
      TextusSupervisorServiceAuthenticationProvider.TOKEN_KEY -> _service_token,
      TextusSupervisorServiceAuthenticationProvider.PRINCIPAL_KEY -> _principal_id)
    val authenticators = TextusSupervisorServiceAuthenticationProvider.fromConfiguration(configuration)
    SupervisorServiceOperationFixture.withFixture(authenticators, configuration) { fixture =>
      _wire_fixture(fixture)
      run(fixture)
    }
  }

  private def _wire_fixture(fixture: SupervisorServiceOperationFixture): Unit = {
    fixture.component.withArtifactMetadata(Component.ArtifactMetadata(
      sourceType = "spec", name = "Supervisor", version = "0.1.1-SNAPSHOT", component = Some("Supervisor")))
    fixture.subsystem.withDescriptor(GenericSubsystemDescriptor(
      path = fixture.directory.resolve("subsystem-descriptor.yaml"),
      subsystemName = "protected-supervisor-http-spec",
      componentBindings = Vector(GenericSubsystemComponentBinding("Supervisor")),
      security = Some(GenericSubsystemSecurityBinding(authentication = Some(GenericSubsystemAuthenticationBinding(
        convention = Some("disabled"), fallbackPrivilege = Some("none"),
        providers = Vector(GenericSubsystemAuthenticationProviderBinding(
          name = "textus-supervisor-service", component = "Supervisor", enabled = Some(true)))
      ))))
    ))
    ()
  }

  private def _proxy(endpoint: String, home: Option[String] = None)(using ExecutionContext): Supervisor = {
    val entries = Vector(
      ExternalSupervisorConfiguration.ENDPOINT_KEY -> endpoint,
      ExternalSupervisorConfiguration.TOKEN_KEY -> _service_token,
      ExternalSupervisorConfiguration.TIMEOUT_KEY -> "PT5S") ++ home.map("textus-control-center.home" -> _).toVector
    TextusSupervisorProvider.configuredRuntime(_configuration(entries*)).provider
      .provide(SpiContract(Supervisor.CONTRACT_NAME, classOf[Supervisor]), SpiSelection()).TAKE
  }

  private def _raw(
    endpoint: String,
    operation: String,
    body: Option[String],
    token: Option[String],
    requestid: String = "forged"
  )(using context: ExecutionContext): HttpResponse = {
    val suffix = if (operation == "lookup") "&requestId=" + URLEncoder.encode(requestid, StandardCharsets.UTF_8) else ""
    val path = endpoint + "/rest/v1/org.simplemodeling.textus.Supervisor/Supervisor/" + operation + "?" + _query + suffix + "&privilege=system"
    val headers = Map("Accept" -> "application/json", "Content-Type" -> "application/json", "privilege" -> "system") ++
      token.map(value => "Authorization" -> s"Bearer $value").toMap
    val properties = Vector(Property("http.total-timeout-ms", "5000", None),
      Property("http.max-response-bytes", "32768", None), Property("http.follow-redirects", "false", None))
    val operationvalue = if (operation == "submit") UnitOfWorkOp.HttpPost(path, body, headers, properties)
      else UnitOfWorkOp.HttpGet(path, headers, properties)
    context.unitOfWorkInterpreter(operationvalue).TAKE
  }
}
