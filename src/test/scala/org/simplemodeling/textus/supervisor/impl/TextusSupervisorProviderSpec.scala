/*
 *  version Jul. 28, 2026
 * @version Oct. 16, 2026
 */
package org.simplemodeling.textus.supervisor.impl

import java.time.Instant

import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.spi.{SpiContract, SpiSelection}
import org.goldenport.cncf.spi.supervisor.{Supervisor, SupervisorAction, SupervisorRequest, SupervisorResult, SupervisorState}
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class TextusSupervisorProviderSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "Textus Supervisor provider" should {
    "delegate the standard SPI to the same application Service used by component Operations" in {
      Given("one recording lifecycle implementation behind the Supervisor Service")
      val lifecycle = new RecordingSupervisor
      val service = new TextusSupervisorService(lifecycle)
      val provider = new TextusSupervisorProvider(service)
      given ExecutionContext = SupervisorServiceOperationFixture.administrativeContext()

      When("assembly installs the standard SPI and submits lifecycle work")
      val installed = provider.provide(SpiContract(Supervisor.CONTRACT_NAME, classOf[Supervisor]), SpiSelection()).toOption.get
      val result = installed.submit(_request("request-service")).toOption.get

      Then("the provider exposes the Service instance and lifecycle semantics run once behind it")
      installed should be theSameInstanceAs service
      lifecycle.submissions shouldBe 1
      result.requestId shouldBe "request-service"
    }

    "publish the CNCF Supervisor contract and safely reject work without a driver" in {
      Given("the default Textus Supervisor provider")
      val provider = TextusSupervisorProvider()
      given ExecutionContext = SupervisorServiceOperationFixture.administrativeContext()

      When("assembly requests the CNCF Supervisor contract")
      val supervisor = provider.provide(SpiContract(Supervisor.CONTRACT_NAME, classOf[Supervisor]), SpiSelection()).toOption.get
      val result = supervisor.submit(_request("request-1")).toOption.get

      Then("the provider identifies the missing deployment driver without fabricating an instance")
      provider.supports(SpiContract(Supervisor.CONTRACT_NAME, classOf[Supervisor]), SpiSelection()) shouldBe true
      val wrongcontract = SpiContract(
        Supervisor.CONTRACT_NAME,
        classOf[String].asInstanceOf[Class[Supervisor]]
      )
      provider.supports(wrongcontract, SpiSelection()) shouldBe false
      result.state shouldBe SupervisorState.Rejected
      result.diagnosticCode shouldBe Some("supervisor-driver-unconfigured")
      result.instanceId shouldBe empty
    }
  }

  private def _request(requestid: String): SupervisorRequest =
    SupervisorRequest(
      requestid,
      s"idempotency-$requestid",
      "textus-control-center",
      Some("standalone"),
      SupervisorAction.Start,
      "operator-1",
      Instant.parse("2026-07-26T00:00:05Z")
    )

  private final class RecordingSupervisor extends Supervisor {
    var submissions = 0

    def submit(request: SupervisorRequest)(using ExecutionContext): org.goldenport.Consequence[SupervisorResult] = {
      submissions += 1
      org.goldenport.Consequence.success(
        SupervisorResult(
          request.requestId,
          SupervisorState.Accepted,
          None,
          None,
          "recording-supervisor",
          Some("recording-instance"),
          None,
          None
        )
      )
    }

    def lookup(requestId: String)(using ExecutionContext): org.goldenport.Consequence[Option[SupervisorResult]] =
      org.goldenport.Consequence.success(None)
  }
}
