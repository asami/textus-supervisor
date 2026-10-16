/*
 * @since   Oct. 16, 2026
 * @version Oct. 16, 2026
 */
package org.simplemodeling.textus.supervisor.impl

import java.nio.file.Files
import org.goldenport.{Conclusion, Consequence}
import org.goldenport.cncf.context.{ExecutionContext, SecurityContext, SubjectKind}
import org.goldenport.cncf.spi.supervisor.{SupervisorAction, SupervisorRequest, SupervisorState}
import org.goldenport.record.Record
import org.scalacheck.{Gen, Prop, Test}
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class SupervisorServiceOperationSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "Supervisor application Service" should {
    "shared generated Operations and SPI" which {
      "share generated submit and SPI durable lookup with one idempotent start" in {
        Given("one real durable Supervisor behind a shared Service, provider and generated Operations")
        SupervisorServiceOperationFixture.withFixture { fixture =>
          val context = SupervisorServiceOperationFixture.administrativeContext()
          val request = SupervisorServiceOperationFixture.request("shared-request")
          val installed = fixture.spi(using context)

          When("the generated command submits, the installed SPI retries, and both surfaces look up the request")
          val submitted = fixture.submit(request, context).TAKE
          val retried = installed.submit(request)(using context).TAKE
          val generatedlookup = fixture.lookup(request.requestId, context).TAKE
          val spilookup = installed.lookup(request.requestId)(using context).TAKE

          Then("both surfaces expose the same persisted typed result and start only one owned child")
          installed should be theSameInstanceAs fixture.service
          submitted shouldBe TextusSupervisorService.result(retried)
          generatedlookup shouldBe Some(submitted)
          spilookup shouldBe Some(retried)
          retried.state shouldBe SupervisorState.Accepted
          retried.instanceId shouldBe Some(fixture.host.children.head.instanceId)
          fixture.backend.requests shouldBe Vector(request, request)
          fixture.backend.lookups shouldBe Vector(request.requestId, request.requestId)
          fixture.host.starts shouldBe 1
          Files.isRegularFile(fixture.statePath) shouldBe true
          _preserved_contexts(fixture, context)
        }
      }

      "reject unauthenticated callers even with administrative-looking claims" in {
        Given("a durable result and an explicitly unauthenticated caller claiming every administrative grant kind")
        SupervisorServiceOperationFixture.withFixture { fixture =>
          val operatorcontext = SupervisorServiceOperationFixture.administrativeContext()
          val retained = SupervisorServiceOperationFixture.request("private-retained-request")
          val context = SupervisorServiceOperationFixture.context(
            authenticated = Some(false),
            privilege = Some(SecurityContext.Privilege.Operator.name),
            capability = Some(SecurityContext.Privilege.System.name),
            role = Some(SecurityContext.Privilege.Internal.name)
          )
          val forged = SupervisorServiceOperationFixture.request("forged-request")
            .copy(operatorSubjectId = "trusted-supervisor-caller")

          When("the operator first persists a result and the unauthenticated caller attempts both entry surfaces")
          fixture.submit(retained, operatorcontext).TAKE
          val before = Files.readString(fixture.statePath)
          val denials = _denied_calls(fixture, context, forged, retained.requestId)
          val after = Files.readString(fixture.statePath)

          Then("structured permission failures disclose no result and never invoke the backend or mutate its state")
          denials.foreach(_permission_denied)
          fixture.backend.requests shouldBe Vector(retained)
          fixture.backend.lookups shouldBe empty
          fixture.backend.contexts should have size 1
          after shouldBe before
          fixture.host.starts shouldBe 1
          fixture.host.preflights shouldBe 1
          fixture.host.recoveries shouldBe 0
          fixture.host.children.map(_.stops) shouldBe Vector(0)
        }
      }

      "reject authenticated ordinary users and launcher-only credentials" in {
        Given("ordinary authenticated users, including a launcher-only grant, and an unspecified caller")
        val contexts = Vector(
          SupervisorServiceOperationFixture.context(privilege = Some(SecurityContext.Privilege.User.name)),
          SupervisorServiceOperationFixture.context(
            privilege = Some(SecurityContext.Privilege.User.name), capability = Some("launcher_registration")
          ),
          SupervisorServiceOperationFixture.context(authenticated = None)
        )
        contexts.foreach { context =>
          SupervisorServiceOperationFixture.withFixture { fixture =>
            val request = SupervisorServiceOperationFixture.request("ordinary-user-request")
              .copy(operatorSubjectId = "operator")

            When("the non-administrative caller submits and looks up valid work through Operations and SPI")
            val denials = _denied_calls(fixture, context, request, request.requestId)

            Then("the payload subject cannot authorize access and the durable backend remains untouched")
            denials.foreach(_permission_denied)
            fixture.backend.requests shouldBe empty
            fixture.backend.lookups shouldBe empty
            fixture.backend.contexts shouldBe empty
            Files.exists(fixture.statePath) shouldBe false
            fixture.host.starts shouldBe 0
            fixture.host.preflights shouldBe 0
            fixture.host.recoveries shouldBe 0
          }
        }
      }

      "accept generated administrative grant combinations" in {
        Given("generated administrative names, grant kinds, authenticated service principals and valid request identities")
        val generator = for {
          grant <- Gen.oneOf(
            SecurityContext.Privilege.ApplicationContentManager.name,
            SecurityContext.Privilege.Operator.name,
            SecurityContext.Privilege.System.name,
            SecurityContext.Privilege.Internal.name
          )
          channel <- Gen.oneOf("privilege", "capability", "role")
          principal <- Gen.uuid
          identity <- Gen.uuid
        } yield (grant, channel, principal.toString, identity.toString)
        val property = Prop.forAll(generator) { case (grant, channel, principalid, requestid) =>
          Given("one generated trusted service context representing a different operator")
          SupervisorServiceOperationFixture.withFixture { fixture =>
            val context = SupervisorServiceOperationFixture.context(
              principalId = principalid,
              privilege = Option.when(channel == "privilege")(grant),
              capability = Option.when(channel == "capability")(grant),
              role = Option.when(channel == "role")(grant),
              subjectKind = SubjectKind.Service
            )
            val request = SupervisorServiceOperationFixture.request(requestid)
            val installed = fixture.spi(using context)

            When("generated and SPI entry points share an exact retry and durable lookup")
            val submitted = fixture.submit(request, context).TAKE
            val retried = installed.submit(request)(using context).TAKE
            val generatedlookup = fixture.lookup(requestid, context).TAKE
            val spilookup = installed.lookup(requestid)(using context).TAKE

            Then("the grant authorizes the actual caller context while every result retains the request and instance")
            installed should be theSameInstanceAs fixture.service
            submitted.requestId.value shouldBe requestid
            submitted shouldBe TextusSupervisorService.result(retried)
            generatedlookup shouldBe Some(submitted)
            spilookup shouldBe Some(retried)
            retried.state shouldBe SupervisorState.Accepted
            retried.instanceId shouldBe Some(fixture.host.children.head.instanceId)
            fixture.backend.requests shouldBe Vector(request, request)
            fixture.backend.lookups shouldBe Vector(requestid, requestid)
            fixture.host.starts shouldBe 1
            _preserved_contexts(fixture, context)
            true
          }
        }

        When("ScalaCheck evaluates at least fifty successful grant, correlation and retry cases")
        val result = Test.check(Test.Parameters.default.withMinSuccessfulTests(60), property)

        Then("the behavioral property passes without ignoring failed or discarded cases")
        result.passed shouldBe true
        result.succeeded should be >= 50
        result.discarded shouldBe 0
      }

      "retain durable results but fail closed on reconstructed unproven ownership" in {
        Given("a real backend and a recording host that cannot recover a child after reconstruction")
        SupervisorServiceOperationFixture.withFixture { fixture =>
          val context = SupervisorServiceOperationFixture.administrativeContext()
          val request = SupervisorServiceOperationFixture.request("before-reconstruction")

          When("an accepted start is persisted before the Service and backend are reconstructed at the same state path")
          val accepted = fixture.submit(request, context).TAKE
          val reconstructed = fixture.reconstruct()
          val installed = reconstructed.spi(using context)
          val generatedlookup = reconstructed.lookup(request.requestId, context).TAKE
          val spilookup = installed.lookup(request.requestId)(using context).TAKE
          val restart = reconstructed.submit(
            SupervisorServiceOperationFixture.request("unproven-restart", SupervisorAction.Restart), context
          ).TAKE
          val stop = installed.submit(
            SupervisorServiceOperationFixture.request("unproven-stop", SupervisorAction.Stop)
          )(using context).TAKE

          Then("durable correlation survives while ownership-sensitive work cannot start or stop a guessed child")
          generatedlookup shouldBe Some(accepted)
          spilookup.map(TextusSupervisorService.result) shouldBe Some(accepted)
          accepted.requestId.value shouldBe request.requestId
          accepted.state.value shouldBe SupervisorState.Accepted.toString
          restart.state.value shouldBe SupervisorState.Rejected.toString
          restart.diagnosticCode.map(_.value) shouldBe Some(StandaloneSupervisorProcessHost.RECOVERY_UNAVAILABLE)
          stop.state shouldBe SupervisorState.Rejected
          stop.diagnosticCode shouldBe Some(StandaloneSupervisorProcessHost.RECOVERY_UNAVAILABLE)
          reconstructed.host.recoveries shouldBe 2
          reconstructed.host.starts shouldBe 0
          reconstructed.host.preflights shouldBe 0
          reconstructed.host.children shouldBe empty
          fixture.host.children.map(_.stops) shouldBe Vector(0)
        }
      }

      "reject invalid generated submit values without reaching backend" in {
        Given("an authenticated operator and generated submit data with absent required fields or unsupported action")
        SupervisorServiceOperationFixture.withFixture { fixture =>
          val context = SupervisorServiceOperationFixture.administrativeContext()
          val malformed = Record.dataAuto("action" -> "start")
          val unsupported = Record.dataAuto(
            "requestId" -> "unsupported-action", "idempotencyKey" -> "unsupported-key",
            "targetId" -> "textus-service-spec", "deploymentId" -> "DEV:service-spec",
            "action" -> "destroy", "operatorSubjectId" -> "represented-operator",
            "deadlineAt" -> java.time.Instant.parse("2030-01-01T00:00:00Z")
          )

          When("normal request resolution and the action engine interpret the invalid generated commands")
          val failures = Vector(malformed, unsupported).map { record =>
            fixture.execute(context, SupervisorServiceOperationFixture.operationRequest("submit", record))
          }

          Then("structured validation failures leave backend submission and process state untouched")
          failures.foreach { result =>
            result shouldBe a[Consequence.Failure[?]]
            result match {
              case Consequence.Failure(conclusion) =>
                conclusion.observation.taxonomy should not be Conclusion.securityPermissionDenied("denied").observation.taxonomy
              case _ => fail("invalid generated submit unexpectedly succeeded")
            }
          }
          fixture.backend.requests shouldBe empty
          fixture.backend.lookups shouldBe empty
          fixture.backend.contexts shouldBe empty
          fixture.host.starts shouldBe 0
          fixture.host.preflights shouldBe 0
          fixture.host.recoveries shouldBe 0
          Files.exists(fixture.statePath) shouldBe false
        }
      }
    }
  }

  private def _denied_calls(
    fixture: SupervisorServiceOperationFixture,
    context: ExecutionContext,
    request: SupervisorRequest,
    lookupid: String
  ): Vector[Consequence[?]] = {
    val installed = fixture.spi(using context)
    Vector(
      fixture.submit(request, context), installed.submit(request)(using context),
      fixture.lookup(lookupid, context), installed.lookup(lookupid)(using context)
    )
  }

  private def _permission_denied(result: Consequence[?]): Unit = result match {
    case Consequence.Failure(conclusion) =>
      conclusion.observation.taxonomy shouldBe Conclusion.securityPermissionDenied("denied").observation.taxonomy
      conclusion.show should include("Lifecycle control requires administrative authorization.")
    case _ => fail("expected a structured permission denial")
  }

  private def _preserved_contexts(fixture: SupervisorServiceOperationFixture, context: ExecutionContext): Unit = {
    fixture.backend.contexts should have size 4
    fixture.actionContexts should have size 2
    fixture.backend.contexts(0) should be theSameInstanceAs fixture.actionContexts(0)
    fixture.backend.contexts(1) should be theSameInstanceAs context
    fixture.backend.contexts(2) should be theSameInstanceAs fixture.actionContexts(1)
    fixture.backend.contexts(3) should be theSameInstanceAs context
    fixture.backend.contexts.foreach { actual =>
      actual.security should be theSameInstanceAs context.security
      actual.clock should be theSameInstanceAs context.clock
    }
  }
}
