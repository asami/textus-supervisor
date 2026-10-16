/*
 * @since   Oct. 16, 2026
 * @version Oct. 16, 2026
 */
package org.simplemodeling.textus.supervisor.impl

import java.net.{InetSocketAddress, URI, URLDecoder}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.concurrent.{ConcurrentLinkedQueue, Executors, Semaphore, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*
import scala.util.Try

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import io.circe.Json
import io.circe.parser.parse
import org.goldenport.Consequence
import org.goldenport.configuration.{Configuration, ConfigurationTrace, ConfigurationValue, ResolvedConfiguration}
import org.goldenport.cncf.component.{ComponentCreate, ComponentOrigin}
import org.goldenport.cncf.context.{Capability, ExecutionContext, SecurityContext, SecurityLevel, SubjectKind}
import org.goldenport.cncf.http.{HttpDriver, UrlConnectionHttpDriver}
import org.goldenport.cncf.security.AuthenticationRequest
import org.goldenport.cncf.spi.{SpiContract, SpiProviderComponent, SpiSelection}
import org.goldenport.cncf.spi.supervisor.{Supervisor, SupervisorAction, SupervisorRequest, SupervisorResult, SupervisorState}
import org.goldenport.cncf.subsystem.Subsystem
import org.goldenport.http.{HttpResponse, HttpStatus}
import org.goldenport.observation.Taxonomy
import org.goldenport.protocol.Property
import org.scalacheck.{Gen, Prop, Test}
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Interface specifications; synthetic peers below specify wire failures, not Service acceptance. */
final class ExternalTextusSupervisorSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  private val _token = "external-spec-service-secret"

  "Configured Supervisor placement (XP1)" should {
    "select explicitly" which {
      "retains the existing unconfigured and configured local paths when external keys are absent" in {
        Given("no external selector and a fresh target-owned local home")
        SupervisorServiceOperationFixture.withFixture { fixture =>
          given ExecutionContext = SupervisorServiceOperationFixture.administrativeContext()
          val empty = _configuration()
          val home = fixture.directory.resolve("local-home")
          val local = _configuration("textus-control-center.home" -> home.toString)

          When("the production provider resolves both local configurations through its shared Service")
          val absent = _spi(empty).submit(SupervisorServiceOperationFixture.request("absent"))
          val configured = _spi(local).submit(SupervisorServiceOperationFixture.request("local"))

          Then("the existing local result semantics and durable local state remain available")
          absent.TAKE.diagnosticCode shouldBe Some("supervisor-driver-unconfigured")
          configured.TAKE.state shouldBe SupervisorState.Rejected
          Files.isRegularFile(home.resolve("textus-supervisor/state.json")) shouldBe true
        }
      }

      "never creates local ownership state for a complete external selection" in {
        Given("a valid external origin, outgoing token, and otherwise usable local home")
        SupervisorServiceOperationFixture.withFixture { fixture =>
          val home = fixture.directory.resolve("consumer-only")
          val driver = new RecordingDriver(call => HttpResponse.text(HttpStatus.Ok, _answer(call)))
          given ExecutionContext = SupervisorServiceOperationFixture.context(httpDriver = Some(driver))
          val configuration = _external("http://127.0.0.1:18099", "PT5S",
            "textus-control-center.home" -> home.toString)

          When("the actual configured SPI submits and looks up through the shared Service")
          val supervisor = _spi(configuration)
          val submitted = supervisor.submit(SupervisorServiceOperationFixture.request("remote-selection"))
          val lookedup = supervisor.lookup("remote-selection")

          Then("only the runtime HTTP driver is used and the local home is not initialized")
          submitted.TAKE.requestId shouldBe "remote-selection"
          lookedup.TAKE.map(_.requestId) shouldBe Some("remote-selection")
          driver.calls.map(_.method) shouldBe Vector("POST", "GET")
          Files.exists(home) shouldBe false
          ExternalSupervisorConfiguration.fromConfiguration(configuration).TAKE.toString should not include _token
          ExternalSupervisorConfiguration.fromConfiguration(configuration).TAKE.toString should not include "127.0.0.1"
        }
      }

      "rejects partial, empty, malformed and composite selections without local fallback" in {
        Given("explicit external keys with invalid origins, tokens, budgets and raw object/list values")
        SupervisorServiceOperationFixture.withFixture { fixture =>
          val home = fixture.directory.resolve("forbidden-local-fallback")
          val driver = new RecordingDriver(_ => throw new IllegalStateException("unexpected dispatch"))
          given ExecutionContext = SupervisorServiceOperationFixture.context(httpDriver = Some(driver))
          val endpointkey = ExternalSupervisorConfiguration.ENDPOINT_KEY
          val tokenkey = ExternalSupervisorConfiguration.TOKEN_KEY
          val timeoutkey = ExternalSupervisorConfiguration.TIMEOUT_KEY
          val complete = Map(endpointkey -> ConfigurationValue.StringValue("https://supervisor.invalid"),
            tokenkey -> ConfigurationValue.StringValue(_token))
          val invalidorigins = Vector("", " ", "relative", "http://localhost", "http://127.0.0.2",
            "http://example.invalid", "https://user:pass@example.invalid", "https://example.invalid/path",
            "https://example.invalid?secret", "https://example.invalid#fragment")
          val invalidtokens = Vector("", " ", "two words", "two\nlines", "bad\u0000token")
          val invalidtimeouts = Vector("", " ", "5s", "PT0S", "-PT1S", "PT0.0001S", "PT30.001S",
            "PT9999999999999999999999S")
          val composites = Vector[ConfigurationValue](ConfigurationValue.ObjectValue(Map.empty),
            ConfigurationValue.ListValue(List(ConfigurationValue.StringValue("secret"))), ConfigurationValue.NullValue)
          val cases = Vector(Map(endpointkey -> ConfigurationValue.StringValue("https://supervisor.invalid")),
            Map(tokenkey -> ConfigurationValue.StringValue(_token)),
            Map(timeoutkey -> ConfigurationValue.StringValue("PT5S"))) ++
            invalidorigins.map(value => complete.updated(endpointkey, ConfigurationValue.StringValue(value))) ++
            invalidtokens.map(value => complete.updated(tokenkey, ConfigurationValue.StringValue(value))) ++
            invalidtimeouts.map(value => complete.updated(timeoutkey, ConfigurationValue.StringValue(value))) ++
            Vector(endpointkey, tokenkey, timeoutkey).flatMap(key => composites.map(value => complete.updated(key, value)))
          cases.foreach { entries =>
            val configuration = _raw_configuration(entries.updated("textus-control-center.home", ConfigurationValue.StringValue(home.toString)))

            When("production configuredRuntime installs the selected backend and its SPI is invoked")
            val supervisor = _spi(configuration)
            val submitted = supervisor.submit(SupervisorServiceOperationFixture.request("invalid-selection"))
            val lookedup = supervisor.lookup("retained")

            Then("both Operations fail safely before either local state or HTTP dispatch")
            _failure(submitted, Taxonomy.serviceUnavailable)
            _failure(lookedup, Taxonomy.serviceUnavailable)
            driver.calls shouldBe empty
            Files.exists(home) shouldBe false
          }
        }
      }

      "keeps explicit empty external properties unavailable instead of using valid lower-priority file settings" in {
        Given("absent raw external keys and a normal configuration file with complete valid external settings")
        SupervisorServiceOperationFixture.withFixture { fixture =>
          val endpointkey = ExternalSupervisorConfiguration.ENDPOINT_KEY
          val tokenkey = ExternalSupervisorConfiguration.TOKEN_KEY
          val timeoutkey = ExternalSupervisorConfiguration.TIMEOUT_KEY
          val keys = Vector(endpointkey, tokenkey, timeoutkey)
          val file = fixture.directory.resolve("external-settings.json")
          Files.writeString(file, Json.obj(endpointkey -> Json.fromString("https://file-supervisor.invalid"),
            tokenkey -> Json.fromString(_token), timeoutkey -> Json.fromString("PT3S")).noSpaces)
          val home = fixture.directory.resolve("file-consumer-only")
          val configuration = _configuration("textus-control-center.home" -> home.toString)
          val explicit = _configuration(endpointkey -> "https://resolved-supervisor.invalid",
            tokenkey -> "resolved-service-secret", timeoutkey -> "PT2S")
          _with_system_properties((Vector("textus.config.file" -> Some(file.toString), "cncf.config.file" -> None) ++
            keys.map(key => key -> None))*) {
            val driver = new RecordingDriver(call => HttpResponse.text(HttpStatus.Ok, _answer(call)))
            given ExecutionContext = SupervisorServiceOperationFixture.context(httpDriver = Some(driver))

            When("the raw-absent selection resolves through the normal file fallback and invokes both Operations")
            val settings = ExternalSupervisorConfiguration.fromConfiguration(configuration)
            val supervisor = _spi(configuration)
            val submitted = supervisor.submit(SupervisorServiceOperationFixture.request("file-fallback"))
            val lookedup = supervisor.lookup("file-fallback")

            Then("valid file values remain usable without initializing local ownership")
            settings.TAKE.endpoint shouldBe "https://file-supervisor.invalid"
            settings.TAKE.token shouldBe _token
            settings.TAKE.timeoutMillis shouldBe 3000L
            submitted.TAKE shouldBe _result("file-fallback")
            lookedup.TAKE shouldBe Some(_result("file-fallback"))
            driver.calls.map(_.method) shouldBe Vector("POST", "GET")
            Files.exists(home) shouldBe false

            keys.foreach { key =>
              Vector("", " \t ").foreach { value =>
                Given("an explicit empty or whitespace exact-key property above the valid configuration file")
                _with_system_properties(key -> Some(value)) {
                  val invaliddriver = new RecordingDriver(_ => throw new IllegalStateException("unexpected dispatch"))
                  given ExecutionContext = SupervisorServiceOperationFixture.context(httpDriver = Some(invaliddriver))

                  When("the provider resolves raw-absent and explicitly valid raw configurations")
                  val selected = ExternalSupervisorConfiguration.selected(configuration)
                  val unavailable = _spi(configuration)
                  val invalidsubmit = unavailable.submit(SupervisorServiceOperationFixture.request("empty-property"))
                  val invalidlookup = unavailable.lookup("empty-property")
                  val resolved = ExternalSupervisorConfiguration.fromConfiguration(explicit)

                  Then("the empty selection fails closed before dispatch while explicit raw values retain precedence")
                  selected shouldBe true
                  _failure(invalidsubmit, Taxonomy.serviceUnavailable)
                  _failure(invalidlookup, Taxonomy.serviceUnavailable)
                  invaliddriver.calls shouldBe empty
                  Files.exists(home) shouldBe false
                  resolved.TAKE.endpoint shouldBe "https://resolved-supervisor.invalid"
                  resolved.TAKE.token shouldBe "resolved-service-secret"
                  resolved.TAKE.timeoutMillis shouldBe 2000L
                }
              }
            }
          }
        }
      }

      "accepts only the frozen literal-loopback and HTTPS origins and integral timeout range" in {
        Given("the complete allowed origin and timeout boundary values")
        val origins = Vector("https://supervisor.invalid", "https://supervisor.invalid/", "http://127.0.0.1:1234", "http://[::1]:1234/")
        val bounds = Vector("PT0.001S" -> 1L, "PT5S" -> 5000L, "PT30S" -> 30000L)

        When("the provider-private parser resolves each complete configuration")
        val settings = for { origin <- origins; (bound, _) <- bounds }
          yield ExternalSupervisorConfiguration.fromConfiguration(_external(origin, bound))

        Then("every permitted origin and inclusive millisecond bound is preserved")
        settings.map(_.TAKE.timeoutMillis) shouldBe origins.flatMap(_ => bounds.map(_._2))
        settings.foreach(_.TAKE.toString shouldBe "ExternalSupervisorConfiguration(<redacted>)")
        ExternalSupervisorConfiguration.fromConfiguration(_configuration(
          ExternalSupervisorConfiguration.ENDPOINT_KEY -> "https://supervisor.invalid",
          ExternalSupervisorConfiguration.TOKEN_KEY -> _token)).TAKE.timeoutMillis shouldBe 5000L
      }
    }
  }

  "Supervisor service authentication (XP1, XP2)" should {
    "use the production component wiring" which {
      "installs only an explicitly valid incoming credential independent of external and Launcher credentials" in {
        Given("the actual production factory and separate incoming, outgoing and Launcher settings")
        val incoming = "incoming-service-secret"
        val base = _external("https://supervisor.invalid", "PT5S",
          "textus-control-center.registration.authentication.token" -> "launcher-secret")
        val configured = _raw_configuration(base.configuration.values ++ Map(
          TextusSupervisorServiceAuthenticationProvider.TOKEN_KEY -> ConfigurationValue.StringValue(incoming),
          TextusSupervisorServiceAuthenticationProvider.PRINCIPAL_KEY -> ConfigurationValue.StringValue("trusted-control-center")))
        given ExecutionContext = SupervisorServiceOperationFixture.administrativeContext()

        When("the factory creates its primary component and that component authenticates request material")
        val emptycomponent = _component(base)
        val component = _component(configured)
        val providers = component.authenticationProviders
        val attempts = Vector(None, Some("wrong"), Some(_token), Some("launcher-secret"), Some(incoming)).map { token =>
          val attributes = Map("operatorSubjectId" -> "forged-operator", "privilege" -> "system") ++
            token.map(value => "authorization" -> s"Bearer $value").toMap
          providers.head.authenticate(AuthenticationRequest(attributes)).TAKE
        }

        Then("only the incoming token establishes the minimal Service identity")
        emptycomponent.authenticationProviders shouldBe empty
        providers.map(_.name) shouldBe Vector("textus-supervisor-service")
        attempts.take(4) shouldBe Vector.fill(4)(None)
        val authenticated = attempts.last.get
        authenticated.principalId.value shouldBe "trusted-control-center"
        authenticated.subjectKind shouldBe SubjectKind.Service
        authenticated.level shouldBe SecurityLevel("service")
        authenticated.capabilities shouldBe Set(Capability(SecurityContext.Privilege.Operator.name))
        authenticated.toSecurityContext.principal.attributes("authenticated") shouldBe "true"
        authenticated.toString should not include incoming
        providers.head.toString should not include incoming
        component.asInstanceOf[SpiProviderComponent].spiProviders should have size 1
        val defaultconfiguration = _configuration(TextusSupervisorServiceAuthenticationProvider.TOKEN_KEY -> incoming)
        TextusSupervisorServiceAuthenticationProvider.fromConfiguration(defaultconfiguration).head
          .authenticate(AuthenticationRequest(Map("authorization" -> incoming))).TAKE.get.principalId.value shouldBe
          "textus-supervisor-control-center"
      }

      "leaves an explicit empty incoming principal unavailable instead of accepting a lower-priority file identity" in {
        Given("absent raw incoming settings and a normal configuration file with a valid token and principal")
        SupervisorServiceOperationFixture.withFixture { fixture =>
          val tokenkey = TextusSupervisorServiceAuthenticationProvider.TOKEN_KEY
          val principalkey = TextusSupervisorServiceAuthenticationProvider.PRINCIPAL_KEY
          val incoming = "file-incoming-service-secret"
          val file = fixture.directory.resolve("incoming-settings.json")
          Files.writeString(file, Json.obj(tokenkey -> Json.fromString(incoming),
            principalkey -> Json.fromString("file-control-center")).noSpaces)
          val configuration = _configuration()
          given ExecutionContext = SupervisorServiceOperationFixture.administrativeContext()
          _with_system_properties("textus.config.file" -> Some(file.toString), "cncf.config.file" -> None,
            tokenkey -> None, principalkey -> None) {

            When("the production component resolves and authenticates the valid file-backed incoming credential")
            val providers = _component(configuration).authenticationProviders
            val authenticated = providers.head.authenticate(AuthenticationRequest(Map("authorization" -> incoming))).TAKE

            Then("normal fallback installs the file's trusted service identity")
            providers.map(_.name) shouldBe Vector("textus-supervisor-service")
            authenticated.map(_.principalId.value) shouldBe Some("file-control-center")
            authenticated.map(_.subjectKind) shouldBe Some(SubjectKind.Service)

            Vector("", " \t ").foreach { value =>
              Given("an empty or whitespace exact incoming-principal property above the valid file identity")
              _with_system_properties(principalkey -> Some(value)) {
                When("the production component attempts to install its incoming authenticator")
                val unavailable = _component(configuration).authenticationProviders

                Then("the explicit invalid principal leaves authentication unavailable")
                unavailable shouldBe empty
              }
            }
          }
        }
      }

      "leaves invalid explicit incoming tokens and principal identities unavailable" in {
        Given("empty, malformed, composite and overlong explicitly configured authentication values")
        val tokenkey = TextusSupervisorServiceAuthenticationProvider.TOKEN_KEY
        val principalkey = TextusSupervisorServiceAuthenticationProvider.PRINCIPAL_KEY
        val valid = Map(tokenkey -> ConfigurationValue.StringValue("incoming-secret"))
        val invalidvalues = Vector[ConfigurationValue](ConfigurationValue.StringValue(" "),
          ConfigurationValue.StringValue("bad\u0000value"), ConfigurationValue.ObjectValue(Map.empty),
          ConfigurationValue.ListValue(Nil), ConfigurationValue.NullValue)
        val cases = invalidvalues.map(value => Map(tokenkey -> value)) ++
          (invalidvalues :+ ConfigurationValue.StringValue("p" * 65)).map(value => valid.updated(principalkey, value))

        When("the production factory attempts to install each explicitly invalid authenticator")
        val providers = cases.map(entries => _component(_raw_configuration(entries)).authenticationProviders)

        Then("no invalid value silently substitutes a trusted identity or a credential")
        providers.foreach(_ shouldBe empty)
      }
    }
  }

  "External Supervisor UoW dispatch (XP3)" should {
    "use the selected runtime driver" which {
      "carries the exact generated Operation contract and all three bounded policies" in {
        Given("the configured production SPI and a recording HttpDriver selected by the real runtime core")
        val driver = new RecordingDriver(call => HttpResponse.text(HttpStatus.Ok, _answer(call)))
        given context: ExecutionContext = SupervisorServiceOperationFixture.context(httpDriver = Some(driver))
        val supervisor = _spi(_external("https://supervisor.invalid", "PT5S"))
        val request = SupervisorServiceOperationFixture.request("query +/&?=é")
          .copy(deadlineAt = context.clock.instant().plusSeconds(2))

        When("the shared Service submits and looks up a represented operator request")
        val submitted = supervisor.submit(request)
        val lookedup = supervisor.lookup(request.requestId)

        Then("only normal POST and GET dispatch occur with safe opaque fields and bounded time")
        submitted.TAKE shouldBe _result(request.requestId)
        lookedup.TAKE shouldBe Some(_result(request.requestId))
        driver.calls.map(_.method) shouldBe Vector("POST", "GET")
        val post = driver.calls.head
        val get = driver.calls.last
        new URI(post.path).getPath shouldBe "/rest/v1/org.simplemodeling.textus.Supervisor/Supervisor/submit"
        new URI(get.path).getPath shouldBe "/rest/v1/org.simplemodeling.textus.Supervisor/Supervisor/lookup"
        Vector(post, get).foreach { call =>
          _query(call.path).filterNot(_._1 == "requestId") shouldBe Map(
            "textus.format" -> "json", "textus.output.shape" -> "data", "textus.command.execution-mode" -> "sync")
          call.headers("Authorization") shouldBe s"Bearer ${_token}"
          call.headers("Accept") shouldBe "application/json"
          val properties = call.properties.map(property => property.name -> property.value.toString).toMap
          properties.keySet shouldBe Set("http.total-timeout-ms", "http.max-response-bytes", "http.follow-redirects")
          properties("http.max-response-bytes") shouldBe "32768"
          properties("http.follow-redirects") shouldBe "false"
          properties("http.total-timeout-ms").toLong should be > 0L
        }
        post.properties.head.value.toString.toLong should be <= 2000L
        get.properties.head.value.toString shouldBe "5000"
        post.headers("Content-Type") shouldBe "application/json"
        _query(get.path)("requestId") shouldBe request.requestId
        val body = parse(post.body.get).toOption.get.hcursor
        body.get[String]("operatorSubjectId").toOption shouldBe Some("represented-operator")
        body.keys.get.toSet shouldBe Set("requestId", "idempotencyKey", "targetId", "deploymentId", "action", "operatorSubjectId", "deadlineAt")
        post.body.get should not include _token
        supervisor.toString should not include _token
      }

      "round trips uppercase and mixed-case HTTP origins through the actual selected driver" in {
        Given("synthetic loopback peers and admitted uppercase or mixed-case origin schemes")
        Vector("HTTP", "Http").foreach { scheme =>
          _with_peer { peer =>
            peer.respond = exchange => _respond(exchange, 200, _answer(peer.calls.last))
            val endpoint = scheme + peer.endpoint.substring(peer.endpoint.indexOf(':'))
            given ExecutionContext = SupervisorServiceOperationFixture.context(httpDriver = Some(new UrlConnectionHttpDriver("")))

            When("the production SPI submits and looks up through the normal runtime driver")
            val supervisor = _spi(_external(endpoint, "PT2S"))
            val submitted = supervisor.submit(SupervisorServiceOperationFixture.request("scheme-round-trip"))
            val lookedup = supervisor.lookup("scheme-round-trip")
            val secured = ExternalSupervisorConfiguration.fromConfiguration(_external("hTtPs://Supervisor.Example:8443/", "PT2S"))

            Then("the loopback peer receives exactly POST and GET and parser-only HTTPS admission canonicalizes its scheme")
            submitted.TAKE shouldBe _result("scheme-round-trip")
            lookedup.TAKE shouldBe Some(_result("scheme-round-trip"))
            peer.calls.map(_.method) shouldBe Vector("POST", "GET")
            secured.TAKE.endpoint shouldBe "https://Supervisor.Example:8443"
          }
        }
      }

      "rejects unauthorized and expired local requests before any driver call" in {
        Given("the shared Service, a selected recording driver, and unauthorized or expired callers")
        val driver = new RecordingDriver(_ => throw new IllegalStateException("unexpected dispatch"))
        val administrative = SupervisorServiceOperationFixture.context(httpDriver = Some(driver))
        val unauthorized = SupervisorServiceOperationFixture.context(authenticated = Some(false), httpDriver = Some(driver))
        val supervisor = _spi(_external("https://supervisor.invalid", "PT5S"))(using administrative)
        val request = SupervisorServiceOperationFixture.request("never-dispatch")

        When("submit and lookup encounter unauthorized context or a remaining sub-millisecond deadline")
        val deniedsubmit = supervisor.submit(request)(using unauthorized)
        val deniedlookup = supervisor.lookup(request.requestId)(using unauthorized)
        val expired = supervisor.submit(request.copy(deadlineAt = administrative.clock.instant().minusSeconds(1)))(using administrative)
        val submillisecond = supervisor.submit(request.copy(deadlineAt = administrative.clock.instant().plusNanos(999999)))(using administrative)

        Then("authorization and deadline failures leave runtime HTTP untouched")
        _failure(deniedsubmit, Taxonomy.securityPermissionDenied)
        _failure(deniedlookup, Taxonomy.securityPermissionDenied)
        _failure(expired, Taxonomy.serviceUnavailable)
        _failure(submillisecond, Taxonomy.serviceUnavailable)
        driver.calls shouldBe empty
      }

      "sanitizes original runtime failures without retaining their Conclusion or exception" in {
        Given("a runtime driver whose original exception includes private transport input")
        val driver = new RecordingDriver(_ => throw new IllegalStateException(s"https://private.invalid ${_token} raw-body-secret"))
        given ExecutionContext = SupervisorServiceOperationFixture.context(httpDriver = Some(driver))
        val supervisor = _spi(_external("https://private.invalid", "PT5S"))

        When("the actual UoW interpreter encounters that transport exception")
        val result = supervisor.lookup("unavailable")

        Then("a fresh structured failure contains only the constant safe diagnostic")
        _failure(result, Taxonomy.serviceUnavailable)
        result.display should include("External Supervisor is unavailable.")
        _safe(result, Vector("private.invalid", "raw-body-secret", _token))
      }
    }
  }

  "External Supervisor bounded wire failures (XP6)" should {
    "map synthetic peer responses" which {
      "maps denied, failed, non-200 and redirected responses without following or retrying" in {
        Given("real synthetic loopback peers returning only status and secret-bearing error input")
        Vector(401, 403, 500, 201, 204, 307).foreach { status =>
          _with_peer { peer =>
            peer.respond = exchange => {
              exchange.getResponseHeaders.add("Location", peer.endpoint + "/redirect-target")
              _respond(exchange, status, "raw-body-secret " + _token)
            }
            given ExecutionContext = SupervisorServiceOperationFixture.context(httpDriver = Some(new UrlConnectionHttpDriver("")))
            val supervisor = _spi(_external(peer.endpoint, "PT1S"))

            When("the external SPI submits and looks up through the selected bounded runtime driver")
            val submitted = supervisor.submit(SupervisorServiceOperationFixture.request("status"))
            val lookedup = supervisor.lookup("status")

            Then("only permission failures or unavailable failures emerge and the target receives no redirect")
            val taxonomy = if (status == 401 || status == 403) Taxonomy.securityPermissionDenied else Taxonomy.serviceUnavailable
            _failure(submitted, taxonomy)
            _failure(lookedup, taxonomy)
            peer.calls should have size 2
            peer.calls.exists(_.path.contains("redirect-target")) shouldBe false
            Vector(submitted, lookedup).foreach(result => _safe(result, Vector(peer.endpoint, _token, "raw-body-secret")))
          }
        }
      }

      "rejects malformed, missing, mistyped, uncorrelated and invalid typed responses" in {
        Given("synthetic status-200 peers with every unusable result category")
        val valid = _result_json(_result("wire"))
        val invalid = Vector("", "not-json", "[]", "null", "{}", "{\"requestId\":5}",
          valid.mapObject(_.remove("supervisorId")).noSpaces,
          valid.mapObject(_.add("requestId", Json.fromString("another-request"))).noSpaces,
          valid.mapObject(_.add("state", Json.fromString("Unknown"))).noSpaces,
          valid.mapObject(_.add("acceptedAt", Json.fromString("yesterday"))).noSpaces,
          valid.mapObject(_.add("instanceId", Json.fromInt(1))).noSpaces,
          valid.mapObject(_.add("supervisorId", Json.fromString(""))).noSpaces,
          valid.mapObject(_.add("diagnostic", Json.fromString("x" * 4097))).noSpaces)
        _with_peer { peer =>
          given ExecutionContext = SupervisorServiceOperationFixture.context(httpDriver = Some(new UrlConnectionHttpDriver("")))
          val supervisor = _spi(_external(peer.endpoint, "PT1S"))
          invalid.foreach { body =>
            peer.respond = exchange => _respond(exchange, 200, body)

            When("submit decodes an unusable synthetic response")
            val result = supervisor.submit(SupervisorServiceOperationFixture.request("wire"))

            Then("the failure is operation-invalid and no peer payload enters diagnostics")
            _failure(result, Taxonomy.operationInvalid)
            _safe(result, Vector(peer.endpoint, _token, "another-request", "yesterday"))
          }
          Vector("[]", "{\"result\":5}", "{\"result\":" + valid.mapObject(_.add("requestId", Json.fromString("other"))).noSpaces + "}")
            .foreach { body =>
              peer.respond = exchange => _respond(exchange, 200, body)

              When("lookup decodes a non-wrapper, mistyped result or mismatched correlation")
              val result = supervisor.lookup("wire")

              Then("lookup rejects the unusable response without inventing a successful result")
              _failure(result, Taxonomy.operationInvalid)
            }
          Vector("{}", "{\"result\":null}").foreach { body =>
            peer.respond = exchange => _respond(exchange, 200, body)

            When("lookup receives an absent or explicitly null result in an object wrapper")
            val result = supervisor.lookup("unknown")

            Then("unknown request evidence remains an ordinary absent result")
            result.TAKE shouldBe None
          }
        }
      }

      "bounds delayed headers, trickled bodies and oversized materialization and releases peer resources" in {
        Given("separate synthetic loopback responders that delay, trickle or exceed the body limit")
        Vector("headers", "trickle", "oversized").foreach { mode =>
          _with_peer { peer =>
            peer.respond = exchange => {
              if (mode == "headers") {
                Thread.sleep(350)
                _respond(exchange, 200, _result_json(_result("bounded")).noSpaces)
              } else if (mode == "trickle") {
                exchange.sendResponseHeaders(200, 0)
                val output = exchange.getResponseBody
                (1 to 80).foreach { _ =>
                  output.write(' '.toInt)
                  output.flush()
                  Thread.sleep(15)
                }
              } else _respond(exchange, 200, "x" * 32769)
            }
            given ExecutionContext = SupervisorServiceOperationFixture.context(httpDriver = Some(new UrlConnectionHttpDriver("")))
            val supervisor = _spi(_external(peer.endpoint, "PT0.1S"))

            When("the selected bounded driver attempts to obtain the complete response")
            val started = System.nanoTime()
            val result = supervisor.lookup("bounded")
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            val released = peer.awaitResponse()

            Then("the operation ends within a finite whole-exchange bound and leaves no active responder")
            _failure(result, Taxonomy.serviceUnavailable)
            elapsed should be < 1500L
            released shouldBe true
            peer.active shouldBe 0
            peer.calls should have size 1
            _safe(result, Vector(peer.endpoint, _token, "x" * 100))
          }
        }
      }
    }
  }

  "Generated Supervisor correlation (XP7)" should {
    "round trip generated values" which {
      "preserves all actions, states, optionals and escaped lookup IDs over the actual selected HTTP driver" in {
        Given("bounded generated identifiers and a synthetic JSON peer using the real provider codec and runtime driver")
        val identifier = Gen.choose(1, 32).flatMap(size => Gen.listOfN(size, Gen.alphaNumChar)).map(_.mkString + " +/&?=éあ")
        _with_peer { peer =>
          given ExecutionContext = SupervisorServiceOperationFixture.context(httpDriver = Some(new UrlConnectionHttpDriver("")))
          val supervisor = _spi(_external(peer.endpoint, "PT2S"))
          val property = Prop.forAll(identifier) { identity =>
            SupervisorState.values.zipWithIndex.foreach { case (state, index) =>
              val action = SupervisorAction.values(index % SupervisorAction.values.length)
              val request = SupervisorServiceOperationFixture.request(identity, action).copy(
                idempotencyKey = "key-" + identity, targetId = "target-" + identity,
                deploymentId = Option.when(index % 2 == 0)("deployment-" + identity))
              val expected = _result(identity).copy(state = state,
                diagnosticCode = Option.when(index % 2 == 0)("code"),
                diagnostic = Option.when(index % 2 == 0)("safe diagnostic"),
                instanceId = Option.when(index % 2 == 0)("instance-" + identity),
                acceptedAt = Option.when(index % 2 == 0)(Instant.parse("2026-10-16T00:00:00Z")),
                completedAt = Option.when(index % 2 != 0)(Instant.parse("2026-10-16T00:00:01Z")))
              peer.respond = exchange => _respond(exchange, 200,
                if (exchange.getRequestMethod == "POST") _result_json(expected).noSpaces
                else Json.obj("result" -> _result_json(expected)).noSpaces)

              When("each generated action is submitted and the same escaped identity is looked up")
              val submitted = supervisor.submit(request)
              val lookedup = supervisor.lookup(identity)
              val calls = peer.calls.takeRight(2)

              Then("every field and optional typed result retains its original correlation")
              submitted.TAKE shouldBe expected
              lookedup.TAKE shouldBe Some(expected)
              val received = parse(calls.head.body.get).toOption.get.hcursor
              received.get[String]("requestId").toOption shouldBe Some(request.requestId)
              received.get[String]("idempotencyKey").toOption shouldBe Some(request.idempotencyKey)
              received.get[String]("targetId").toOption shouldBe Some(request.targetId)
              received.get[Option[String]]("deploymentId").toOption.get shouldBe request.deploymentId
              received.get[String]("action").toOption shouldBe Some(action.toString.toLowerCase(java.util.Locale.ROOT))
              received.get[String]("operatorSubjectId").toOption shouldBe Some(request.operatorSubjectId)
              received.get[String]("deadlineAt").toOption shouldBe Some(request.deadlineAt.toString)
              _query(calls.last.path)("requestId") shouldBe identity
              SupervisorOperationJson.decodeSubmit(_result_json(expected.copy(requestId = "mismatch")).noSpaces, identity).isFaillure shouldBe true
            }
            true
          }

          When("ScalaCheck evaluates fifty successful generated round trips without filtering")
          val result = Test.check(Test.Parameters.default.withMinSuccessfulTests(50), property)

          Then("the behavior property succeeds with at least fifty successes and no discarded input")
          result.passed shouldBe true
          result.succeeded should be >= 50
          result.discarded shouldBe 0
        }
      }
    }
  }

  private def _with_system_properties[A](entries: (String, Option[String])*)(run: => A): A = {
    val previous = entries.map { case (key, _) => key -> Option(System.getProperty(key)) }
    try {
      entries.foreach {
        case (key, Some(value)) => System.setProperty(key, value)
        case (key, None) => System.clearProperty(key)
      }
      run
    } finally {
      previous.foreach {
        case (key, Some(value)) => System.setProperty(key, value)
        case (key, None) => System.clearProperty(key)
      }
    }
  }

  private def _configuration(entries: (String, String)*): ResolvedConfiguration =
    _raw_configuration(entries.map { case (key, value) => key -> ConfigurationValue.StringValue(value) }.toMap)

  private def _raw_configuration(entries: Map[String, ConfigurationValue]): ResolvedConfiguration =
    ResolvedConfiguration(Configuration(entries), ConfigurationTrace.empty)

  private def _external(endpoint: String, timeout: String, entries: (String, String)*): ResolvedConfiguration =
    _configuration((Vector(ExternalSupervisorConfiguration.ENDPOINT_KEY -> endpoint,
      ExternalSupervisorConfiguration.TOKEN_KEY -> _token, ExternalSupervisorConfiguration.TIMEOUT_KEY -> timeout) ++ entries)*)

  private def _spi(configuration: ResolvedConfiguration)(using ExecutionContext): Supervisor =
    TextusSupervisorProvider.configuredRuntime(configuration).provider
      .provide(SpiContract(Supervisor.CONTRACT_NAME, classOf[Supervisor]), SpiSelection()).TAKE

  private def _component(configuration: ResolvedConfiguration): org.goldenport.cncf.component.Component =
    new ComponentFactory().create(ComponentCreate(Subsystem(name = "external-provider-spec", configuration = configuration), ComponentOrigin.Main)).primary

  private def _result(identity: String): SupervisorResult = SupervisorResult(identity, SupervisorState.Running,
    None, None, "remote-supervisor", Some("remote-instance"), Some(Instant.parse("2026-10-16T00:00:00Z")), None)

  private def _result_json(result: SupervisorResult): Json = Json.obj(
    "requestId" -> Json.fromString(result.requestId), "state" -> Json.fromString(result.state.toString),
    "diagnosticCode" -> result.diagnosticCode.map(Json.fromString).getOrElse(Json.Null),
    "diagnostic" -> result.diagnostic.map(Json.fromString).getOrElse(Json.Null),
    "supervisorId" -> Json.fromString(result.supervisorId),
    "instanceId" -> result.instanceId.map(Json.fromString).getOrElse(Json.Null),
    "acceptedAt" -> result.acceptedAt.map(value => Json.fromString(value.toString)).getOrElse(Json.Null),
    "completedAt" -> result.completedAt.map(value => Json.fromString(value.toString)).getOrElse(Json.Null))

  private def _answer(call: CapturedCall): String = {
    val identity = if (call.method == "POST") parse(call.body.get).toOption.get.hcursor.get[String]("requestId").toOption.get
      else _query(call.path)("requestId")
    val result = _result_json(_result(identity))
    (if (call.method == "POST") result else Json.obj("result" -> result)).noSpaces
  }

  private def _query(path: String): Map[String, String] = Option(new URI(path).getRawQuery).toVector
    .flatMap(_.split("&")).map { entry =>
      val pair = entry.split("=", 2)
      URLDecoder.decode(pair(0), StandardCharsets.UTF_8) -> URLDecoder.decode(pair.lift(1).getOrElse(""), StandardCharsets.UTF_8)
    }.toMap

  private def _failure(result: Consequence[?], taxonomy: Taxonomy): Unit = result match {
    case Consequence.Failure(conclusion) =>
      conclusion.observation.taxonomy shouldBe taxonomy
      conclusion.previous shouldBe None
      conclusion.getException shouldBe None
    case _ => fail("A structured failure was required")
  }

  private def _safe(result: Consequence[?], secrets: Vector[String]): Unit =
    secrets.foreach(secret => (result.toString + result.display + result.show) should not include secret)

  private final case class CapturedCall(method: String, path: String, body: Option[String], headers: Map[String, String], properties: Vector[Property])

  private final class RecordingDriver(response: CapturedCall => HttpResponse) extends HttpDriver {
    private var _calls = Vector.empty[CapturedCall]
    def calls: Vector[CapturedCall] = _calls
    def get(path: String, headers: Map[String, String], properties: Vector[Property]): HttpResponse =
      _dispatch(CapturedCall("GET", path, None, headers, properties))
    def post(path: String, body: Option[String], headers: Map[String, String], properties: Vector[Property]): HttpResponse =
      _dispatch(CapturedCall("POST", path, body, headers, properties))
    def put(path: String, body: Option[String], headers: Map[String, String], properties: Vector[Property]): HttpResponse =
      throw new UnsupportedOperationException("PUT is outside the Supervisor contract")
    private def _dispatch(call: CapturedCall): HttpResponse = {
      _calls = _calls :+ call
      response(call)
    }
  }

  private final class WirePeer {
    private val _calls = new ConcurrentLinkedQueue[CapturedCall]
    private val _active = new AtomicInteger(0)
    private val _completed = new Semaphore(0)
    private val _executor = Executors.newCachedThreadPool()
    private val _server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    @volatile var respond: HttpExchange => Unit = exchange => _respond(exchange, 500, "unconfigured synthetic peer")
    _server.setExecutor(_executor)
    _server.createContext("/", new HttpHandler {
      def handle(exchange: HttpExchange): Unit = {
        _active.incrementAndGet()
        try {
          val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
          _calls.add(CapturedCall(exchange.getRequestMethod, exchange.getRequestURI.toASCIIString,
            Option.when(exchange.getRequestMethod == "POST")(body), Map.empty, Vector.empty))
          respond(exchange)
        } catch {
          case _: java.io.IOException => () // Expected when the bounded client cancels a synthetic responder.
        } finally {
          exchange.close()
          _active.decrementAndGet()
          _completed.release()
        }
      }
    })
    _server.start()
    def endpoint: String = s"http://127.0.0.1:${_server.getAddress.getPort}"
    def calls: Vector[CapturedCall] = _calls.iterator().asScala.toVector
    def active: Int = _active.get()
    def awaitResponse(): Boolean = _completed.tryAcquire(3, TimeUnit.SECONDS)
    def close(): Unit = {
      _server.stop(0)
      _executor.shutdownNow()
      _executor.awaitTermination(5, TimeUnit.SECONDS)
      ()
    }
  }

  private def _with_peer[A](run: WirePeer => A): A = {
    val peer = new WirePeer
    try run(peer)
    finally peer.close()
  }

  private def _respond(exchange: HttpExchange, status: Int, body: String): Unit = {
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.set("Content-Type", "application/json; charset=UTF-8")
    exchange.sendResponseHeaders(status, if (status == 204) -1L else bytes.length.toLong)
    if (status != 204) exchange.getResponseBody.write(bytes)
  }
}
