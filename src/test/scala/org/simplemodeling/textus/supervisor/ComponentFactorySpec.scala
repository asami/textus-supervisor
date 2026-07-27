/*
 * @since   Jul. 28, 2026
 * @version Jul. 28, 2026
 * @author  ASAMI, Tomoharu
 */
package org.simplemodeling.textus.supervisor

import java.nio.file.{Files, Path}

import org.goldenport.configuration.{Configuration, ConfigurationTrace, ResolvedConfiguration}
import org.goldenport.cncf.component.{ComponentCreate, ComponentOrigin}
import org.goldenport.cncf.spi.SpiProviderComponent
import org.goldenport.cncf.subsystem.Subsystem
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class ComponentFactorySpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "Textus Supervisor primary component" should {
    "model the real Supervisor Service without a dummy status surface or Componentlet" in {
      Given("the authoritative Supervisor CML")
      val cml = Files.readString(Path.of("src", "main", "cozy", "textus-supervisor.cml"))

      When("the lifecycle component boundary is inspected")
      val component = new TextusSupervisorComponent()
      val operations = component.operationDefinitions.map(_.name).toSet
      val definition = component.componentDefinitionRecords.head

      Then("only submit and lookup form the generated lifecycle Service")
      operations shouldBe Set("submit", "lookup")
      component.operationDefinitions.map(_.visibility).toSet shouldBe Set(Some("protected"))
      definition.getAny("componentlets") shouldBe Some(Vector.empty)
      cml should include("#### Supervisor")
      cml should include("- spi-standard :: cncf.supervisor")
      cml should not include "SupervisorStatus"
      cml should not include "getSupervisorStatus"
      cml should not include "embedded-supervisor"
      cml should not include "# COMPONENTLET"
    }

    "publish one primary participant and no Componentlet factory" in {
      Given("the CAR bundle factory")
      val factory = new impl.ComponentFactory()
      val subsystem = new Subsystem(
        "textus-supervisor-component-spec",
        configuration = ResolvedConfiguration(Configuration.empty, ConfigurationTrace.empty)
      )

      When("its runtime participants are inspected")
      val primary = factory.primaryFactory
      val componentlets = factory.componentletFactories
      val component = factory.create(ComponentCreate(subsystem, ComponentOrigin.Main)).primary

      Then("the real primary component is the sole participant and publishes one standard SPI adapter")
      primary should not be null
      componentlets shouldBe empty
      component shouldBe a[SpiProviderComponent]
      component.asInstanceOf[SpiProviderComponent].spiProviders should have size 1
      component.port.outputEntries shouldBe empty
    }
  }
}
