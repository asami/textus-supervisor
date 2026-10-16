/*
 * @since   Oct. 17, 2026
 * @version Oct. 17, 2026
 */
package org.simplemodeling.textus.supervisor.impl

import java.net.{InetAddress, InetSocketAddress, ServerSocket}
import java.time.Instant

import org.goldenport.Consequence
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.spi.supervisor.{SupervisorAction, SupervisorState}
import org.goldenport.observation.Taxonomy
import org.scalacheck.{Gen, Prop, Test}
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Actual canonical child acceptance at the shared Service/process boundary. */
final class CanonicalSupervisorProcessSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  private val _suffix = Gen.nonEmptyListOf(Gen.oneOf("abcXYZ0129._-:+".toVector)).map(_.take(10).mkString)

  Vector("embedded", "external").foreach { placement =>
    s"Canonical Supervisor $placement placement" should {
      "recover exact durable ownership (CP1)" which {
        "runs at least three complete generated real-child lifecycles without discarded cases" in {
          Given("natively prepared admitted runtime and component checkouts with valid opaque request suffixes")
          When("three generated cases each start, reconstruct, restart and stop a genuine canonical server")
          val result = Test.check(Test.Parameters.default.withMinSuccessfulTests(3).withWorkers(1),
            Prop.forAll(_suffix) { suffix =>
              CanonicalSupervisorProcessFixture.withFixture { fixture =>
                withClue(s"placement=$placement case=${fixture.directory}: ") {
                  _lifecycle(fixture, placement, suffix)
                }
              }
              true
            })
          Then("every successful case crossed the real process boundary and no input was discarded")
          info(s"CP1 $placement successful=${result.succeeded} discarded=${result.discarded}")
          withClue(s"CP1 placement=$placement status=${result.status}: ") {
            result.passed shouldBe true
          }
          result.succeeded should be >= 3
          result.discarded shouldBe 0
        }
      }

      "retain occupied-port rejection (CP2)" which {
        "replays the same durable rejection after the conflict is released without launching" in {
          Given("a fresh real backend and a ServerSocket occupying its explicitly registered child port")
          CanonicalSupervisorProcessFixture.withFixture { fixture =>
            given ExecutionContext = CanonicalSupervisorServiceFixture.administrativeContext()
            val service = new CanonicalSupervisorServiceFixture(fixture)
            val conflict = new ServerSocket()
            conflict.setReuseAddress(false)
            conflict.bind(new InetSocketAddress(InetAddress.getLoopbackAddress, fixture.port))
            try service.withEntry(placement) { entry =>
              val request = fixture.request("occupied-port")
              When("Start is submitted while the real port is held")
              val rejected = entry.submit(request).TAKE
              Then("the port conflict is durably rejected without an invocation")
              rejected.state shouldBe SupervisorState.Rejected
              rejected.diagnosticCode shouldBe Some(StandaloneSupervisorProcessHost.PORT_UNAVAILABLE)
              rejected.instanceId shouldBe None
              fixture.evidence() shouldBe empty
              When("the conflicting socket closes and exactly the same request is retried")
              conflict.close()
              val retry = entry.submit(request).TAKE
              val retained = entry.lookup(request.requestId).TAKE
              Then("the original rejection remains exact and the released port stays free")
              retry shouldBe rejected
              retained shouldBe Some(rejected)
              fixture.evidence() shouldBe empty
              fixture.portAvailable shouldBe true
            } finally conflict.close()
          }
        }
      }

      "release acquired children after a primary failure (CP4)" which {
        "preserves the original exception, retains case evidence and releases the real port" in {
          Given("a real canonical profile and a known primary failure after an accepted Start")
          val primary = new IllegalStateException("Deliberate canonical case failure")
          var retained = Option.empty[CanonicalSupervisorProcessFixture]
          When("the scenario throws after the genuine child has started through the selected Service entry")
          val observed = intercept[IllegalStateException] {
            CanonicalSupervisorProcessFixture.withFixture { fixture =>
              retained = Some(fixture)
              given ExecutionContext = CanonicalSupervisorServiceFixture.administrativeContext()
              val service = new CanonicalSupervisorServiceFixture(fixture)
              service.withEntry(placement) { entry =>
                val started = entry.submit(fixture.request("primary-failure")).TAKE
                Then("Start names an accepted live native invocation before the deliberate failure")
                started.state shouldBe SupervisorState.Accepted
                started.instanceId should not be empty
                val evidence = fixture.awaitEvidence(started.instanceId.get, stopped = false)
                _running(evidence, fixture)
                fixture.portReady shouldBe true
                When("the known primary exception interrupts the case")
                throw primary
              }
            }
          }
          Then("the same primary exception escapes after every acquired child has been stopped")
          observed should be theSameInstanceAs primary
          val fixture = retained.get
          java.nio.file.Files.isDirectory(fixture.directory) shouldBe true
          fixture.evidence() should not be empty
          fixture.evidence().forall(entry => !fixture.nativeAlive(entry)) shouldBe true
          fixture.portAvailable shouldBe true
        }
      }

      "guard the real child boundary (CP3)" which {
        "denies unauthorized callers and preserves each placement's expired-request contract" in {
          Given("a real profile, administrative and unauthenticated contexts, and an expired lifecycle request")
          CanonicalSupervisorProcessFixture.withFixture { fixture =>
            val administrative = CanonicalSupervisorServiceFixture.administrativeContext()
            val unauthorized = CanonicalSupervisorServiceFixture.unauthenticatedContext()
            val service = new CanonicalSupervisorServiceFixture(fixture)
            service.withEntry(placement) { entry =>
              val request = fixture.request("guarded-start")
              val expiredrequest = fixture.request("expired-start").copy(deadlineAt = Instant.now().minusSeconds(60))
              When("an unauthenticated caller attempts submit and lookup through the shared Service guard")
              val denied = entry.submit(request)(using unauthorized)
              val deniedlookup = entry.lookup(request.requestId)(using unauthorized)
              Then("the existing permission taxonomy denies access without creating process evidence")
              _failure(denied, Taxonomy.securityPermissionDenied, fixture)
              _failure(deniedlookup, Taxonomy.securityPermissionDenied, fixture)
              fixture.evidence() shouldBe empty
              fixture.portAvailable shouldBe true
              When("an authorized caller submits an already expired request")
              val expired = entry.submit(expiredrequest)(using administrative)
              val retained = entry.lookup(expiredrequest.requestId)(using administrative).TAKE
              val unauthorizedretained = entry.lookup(request.requestId)(using administrative).TAKE
              Then("embedded timeout is durable while the external proxy rejects before dispatch; neither launches")
              if (placement == "embedded") {
                val timedout = expired.TAKE
                timedout.state shouldBe SupervisorState.TimedOut
                timedout.diagnosticCode shouldBe Some("supervisor-request-timed-out")
                timedout.instanceId shouldBe None
                retained shouldBe Some(timedout)
                _safe(timedout.toString, fixture)
              } else {
                _failure(expired, Taxonomy.serviceUnavailable, fixture)
                retained shouldBe None
              }
              unauthorizedretained shouldBe None
              fixture.evidence() shouldBe empty
              fixture.portAvailable shouldBe true
            }(using administrative)
          }
        }
      }
    }
  }

  private def _lifecycle(fixture: CanonicalSupervisorProcessFixture, placement: String, suffix: String): Unit = {
    Given("a fresh case-owned home and durable backend registered to the actual Supervisor checkout and chosen port")
    given ExecutionContext = CanonicalSupervisorServiceFixture.administrativeContext()
    val service = new CanonicalSupervisorServiceFixture(fixture)
    val startrequest = fixture.request("start-" + suffix)
    val started = service.withEntry(placement) { entry =>
      When("Start and its exact retry cross the selected shared Service entry")
      val result = entry.submit(startrequest).TAKE
      val retry = entry.submit(startrequest).TAKE
      val retained = entry.lookup(startrequest.requestId).TAKE
      Then("both requests name one accepted real child rather than a duplicate launch")
      result.state shouldBe SupervisorState.Accepted
      result.instanceId should not be empty
      retry shouldBe result
      retained shouldBe Some(result)
      _safe(result.toString, fixture)
      When("the actual Launcher evidence, native identity, TCP port and one-shot safe projection are observed")
      val evidence = fixture.awaitEvidence(result.instanceId.get, stopped = false)
      val listing = fixture.safeList()
      val entries = fixture.evidence()
      val ready = fixture.portReady
      Then("one live native invocation matches the exact artifact, directory and instance and its safe projection hides private facts")
      _running(evidence, fixture)
      ready shouldBe true
      entries should have size 1
      listing.hcursor.get[String]("schema").toOption shouldBe Some("cncf.launcher.evidence-projection.v1")
      listing.hcursor.downField("entries").focus.get.asArray.get should have size 1
      listing.hcursor.downField("entries").downArray.get[String]("instanceId").toOption shouldBe result.instanceId
      listing.noSpaces should not include "developmentDirectory"
      listing.noSpaces should not include "processId"
      listing.noSpaces should not include "processStartedAt"
      listing.noSpaces should not include fixture.componentRoot.toString
      result
    }

    Given("the first consumer's HTTP resources are closed and a new backend uses exactly the same state and profile")
    val reconstructed = service.reconstruct()
    reconstructed.withEntry(placement) { entry =>
      When("the reconstructed entry looks up and replays the retained Start")
      val retained = entry.lookup(startrequest.requestId).TAKE
      val replay = entry.submit(startrequest).TAKE
      val before = fixture.awaitEvidence(started.instanceId.get, stopped = false)
      val entries = fixture.evidence()
      Then("durable result and live native child remain exact without a replacement invocation")
      retained shouldBe Some(started)
      replay shouldBe started
      entries should have size 1
      _running(before, fixture)
      fixture.portReady shouldBe true

      Given("the reconstructed backend must recover its owned child using actual Launcher native evidence")
      val restartrequest = fixture.request("restart-" + suffix, SupervisorAction.Restart)
      When("Restart and the identical retry are submitted through the reconstructed entry")
      val restarted = entry.submit(restartrequest).TAKE
      val restartretry = entry.submit(restartrequest).TAKE
      Then("Restart is accepted once with a distinct owned instance and the exact retry result")
      restarted.state shouldBe SupervisorState.Accepted
      restarted.instanceId should not be empty
      restarted.instanceId should not be started.instanceId
      restartretry shouldBe restarted
      _safe(restarted.toString, fixture)
      When("normal termination of the prior invocation and readiness of its replacement are observed")
      val previous = fixture.awaitEvidence(started.instanceId.get, stopped = true)
      val current = fixture.awaitEvidence(restarted.instanceId.get, stopped = false)
      val ready = fixture.portReady
      Then("only the proven prior child stopped and one distinct genuine child owns the selected port")
      previous.processId shouldBe before.processId
      previous.processStartedAt shouldBe before.processStartedAt
      fixture.nativeAlive(previous) shouldBe false
      current.processId should not be previous.processId
      _running(current, fixture)
      ready shouldBe true
      fixture.evidence() should have size 2

      Given("the current child is owned by the reconstructed Supervisor")
      val stoprequest = fixture.request("stop-" + suffix, SupervisorAction.Stop)
      When("Stop and its identical retry are submitted and the final evidence is observed")
      val stopped = entry.submit(stoprequest).TAKE
      val stopretry = entry.submit(stoprequest).TAKE
      val retainedstop = entry.lookup(stoprequest.requestId).TAKE
      val finalentry = fixture.awaitEvidence(restarted.instanceId.get, stopped = true)
      val available = fixture.portAvailable
      Then("the exact current child and port are released while stopped evidence and all retry results remain durable")
      stopped.state shouldBe SupervisorState.Stopped
      stopped.instanceId shouldBe restarted.instanceId
      stopretry shouldBe stopped
      retainedstop shouldBe Some(stopped)
      finalentry.processId shouldBe current.processId
      finalentry.processStartedAt shouldBe current.processStartedAt
      fixture.nativeAlive(finalentry) shouldBe false
      available shouldBe true
      fixture.evidence().forall(_.stoppedAt.nonEmpty) shouldBe true
      fixture.evidence() should have size 2
      _safe(stopped.toString, fixture)
    }
  }

  private def _running(entry: CanonicalProcessEvidence, fixture: CanonicalSupervisorProcessFixture): Unit = {
    entry.artifactId shouldBe Some("textus-supervisor")
    entry.executionMode shouldBe "development"
    entry.developmentDirectory shouldBe Some(fixture.componentRoot.toString)
    entry.processId.getOrElse(0L) should be > 0L
    entry.processStartedAt should not be empty
    entry.stoppedAt shouldBe None
    fixture.nativeAlive(entry) shouldBe true
  }

  private def _failure(result: Consequence[?], taxonomy: Taxonomy, fixture: CanonicalSupervisorProcessFixture): Unit = result match {
    case Consequence.Failure(conclusion) =>
      conclusion.observation.taxonomy shouldBe taxonomy
      _safe(conclusion.show, fixture)
    case _ => fail("Expected the existing structured failure")
  }

  private def _safe(text: String, fixture: CanonicalSupervisorProcessFixture): Unit = {
    text should not include CanonicalSupervisorServiceFixture.serviceToken
    text should not include fixture.home.toString
    text should not include fixture.componentRoot.toString
    text should not include "--runtime-dev-dir"
    text should not include "--textus.server.port"
  }
}
