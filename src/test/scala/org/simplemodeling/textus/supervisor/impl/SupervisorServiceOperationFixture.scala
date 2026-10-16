/*
 * @since   Oct. 16, 2026
 * @version Oct. 16, 2026
 */
package org.simplemodeling.textus.supervisor.impl

import java.nio.file.{Files, Path}
import java.time.Instant
import scala.jdk.CollectionConverters.*

import cats.~>
import org.goldenport.Consequence
import org.goldenport.configuration.{Configuration, ConfigurationTrace, ResolvedConfiguration}
import org.goldenport.cncf.action.Action
import org.goldenport.cncf.component.{Component, ComponentCreate, ComponentOrigin}
import org.goldenport.cncf.context.{Capability, DataStoreContext, EntityStoreContext, ExecutionContext, Principal, PrincipalId, RuntimeContext, SecurityContext, SubjectKind}
import org.goldenport.cncf.datastore.{DataStore, DataStoreSpace}
import org.goldenport.cncf.entity.EntityStoreSpace
import org.goldenport.cncf.event.EventEngine
import org.goldenport.cncf.http.HttpDriver
import org.goldenport.cncf.security.AuthenticationProvider
import org.goldenport.cncf.spi.{SpiContract, SpiSelection}
import org.goldenport.cncf.spi.supervisor.{Supervisor, SupervisorAction, SupervisorRequest, SupervisorResult}
import org.goldenport.cncf.subsystem.Subsystem
import org.goldenport.cncf.unitofwork.{CommitRecorder, UnitOfWork, UnitOfWorkInterpreter, UnitOfWorkOp}
import org.goldenport.protocol.{Property, Request}
import org.goldenport.protocol.operation.OperationResponse
import org.goldenport.record.Record
import org.simplemodeling.textus.supervisor.{TextusSupervisorComponent, value}

final class SupervisorServiceOperationFixture private (
  val directory: Path,
  val host: SupervisorServiceOperationFixture.RecordingProcessHost,
  private val _authentication_providers: Vector[AuthenticationProvider],
  private val _configuration: ResolvedConfiguration
) {
  val statePath: Path = directory.resolve("supervisor-state.json")
  private val _standalone = new StandaloneTextusSupervisor(statePath, host)
  _standalone.registerDevelopmentProfile(
    StandaloneDevelopmentLaunchProfile("textus-service-spec", directory, 18011, "DEV:service-spec")
  )
  val backend = new SupervisorServiceOperationFixture.RecordingSupervisor(_standalone)
  val service = new TextusSupervisorService(backend)
  val provider = new TextusSupervisorProvider(service)
  private val _subsystem = Subsystem(
    name = "supervisor-service-operation-spec",
    configuration = _configuration
  )
  private val _factory = new ServiceParticipantFactory
  private val _participant = _factory.createPrimary(ComponentCreate(_subsystem, ComponentOrigin.Main))
  val component: Component = new org.goldenport.cncf.component.ComponentFactory().bootstrap(_participant)
  _subsystem.add(component)
  private var _action_contexts = Vector.empty[ExecutionContext]

  def actionContexts: Vector[ExecutionContext] = _action_contexts

  def subsystem: Subsystem = _subsystem

  def spi(using ExecutionContext): Supervisor =
    provider.provide(SpiContract(Supervisor.CONTRACT_NAME, classOf[Supervisor]), SpiSelection()).TAKE

  def submit(request: SupervisorRequest, context: ExecutionContext): Consequence[value.SupervisorResult] = {
    val record = Record.dataAuto(
      "requestId" -> request.requestId,
      "idempotencyKey" -> request.idempotencyKey,
      "targetId" -> request.targetId,
      "deploymentId" -> request.deploymentId,
      "action" -> request.action.toString.toLowerCase,
      "operatorSubjectId" -> request.operatorSubjectId,
      "deadlineAt" -> request.deadlineAt
    )
    value.SupervisorRequest.createC(record).flatMap { input =>
      execute(context, SupervisorServiceOperationFixture.operationRequest("submit", input.toRecord()))
        .flatMap(_record).flatMap(value.SupervisorResult.createC)
    }
  }

  def lookup(requestId: String, context: ExecutionContext): Consequence[Option[value.SupervisorResult]] =
    value.SupervisorLookup.createC(Record.dataAuto("requestId" -> requestId)).flatMap { input =>
      execute(context, SupervisorServiceOperationFixture.operationRequest("lookup", input.toRecord()))
        .flatMap(_record).flatMap(value.SupervisorLookupResult.createC).map(_.result)
    }

  def execute(context: ExecutionContext, request: Request): Consequence[OperationResponse] =
    component.logic.makeOperationRequest(request).flatMap {
      case action: Action =>
        val call = component.logic.createActionCall(action, context)
        _action_contexts = _action_contexts :+ call.executionContext
        component.actionEngine.execute(call)
      case _ => Consequence.operationInvalid("Supervisor request did not resolve to an Action")
    }

  def reconstruct(): SupervisorServiceOperationFixture =
    new SupervisorServiceOperationFixture(
      directory,
      new SupervisorServiceOperationFixture.RecordingProcessHost,
      _authentication_providers,
      _configuration
    )

  def close(): Unit = {
    val paths = Files.walk(directory)
    try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete)
    finally paths.close()
  }

  private def _record(response: OperationResponse): Consequence[Record] = response match {
    case record: OperationResponse.RecordResponse => Consequence.success(record.record)
    case _ => Consequence.operationInvalid("Supervisor operation did not return a Record")
  }

  private final class ServiceParticipantFactory
    extends TextusSupervisorParticipantFactoryBase with Component.PrimaryComponentFactory {
    override val Supervisor: TextusSupervisorComponent.SupervisorServiceFactory =
      new DefaultSupervisorServiceFactory(() => Some(service))

    override protected def create_Component(params: ComponentCreate): Component =
      new TextusSupervisorPrimaryComponent(provider, _authentication_providers)

    override protected def create_Core(params: ComponentCreate, comp: Component): Component.Core =
      component_core(TextusSupervisorComponent.name, TextusSupervisorComponent.componentId)
  }
}

object SupervisorServiceOperationFixture {
  def withFixture[A](run: SupervisorServiceOperationFixture => A): A =
    withFixture(Vector.empty, ResolvedConfiguration(Configuration.empty, ConfigurationTrace.empty))(run)

  def withFixture[A](
    authenticationProviders: Vector[AuthenticationProvider],
    configuration: ResolvedConfiguration
  )(run: SupervisorServiceOperationFixture => A): A = {
    val base = Path.of("target", "test-work", "cc4-s3a").toAbsolutePath.normalize
    Files.createDirectories(base)
    val fixture = new SupervisorServiceOperationFixture(
      Files.createTempDirectory(base, "service-"),
      new RecordingProcessHost,
      authenticationProviders,
      configuration
    )
    try run(fixture)
    finally fixture.close()
  }

  def administrativeContext(): ExecutionContext = context()

  def context(
    authenticated: Option[Boolean] = Some(true),
    principalId: String = "trusted-supervisor-caller",
    privilege: Option[String] = Some(SecurityContext.Privilege.Operator.name),
    capability: Option[String] = None,
    role: Option[String] = None,
    subjectKind: SubjectKind = SubjectKind.User,
    httpDriver: Option[HttpDriver] = None
  ): ExecutionContext = {
    val base = ExecutionContext.create()
    val datastorespace = new DataStoreSpace().addDataStore(DataStore.inMemorySearchable())
    val entitystorespace = EntityStoreSpace.create(ResolvedConfiguration(Configuration.empty, ConfigurationTrace.empty))
    val core = RuntimeContext.core(
      "supervisor-service-operation-spec",
      None,
      base.observability,
      httpdriveroption = httpDriver,
      datastore = Some(DataStoreContext(datastorespace)),
      entitystore = Some(EntityStoreContext(entitystorespace))
    )
    val principalattributes = authenticated.map(flag => "authenticated" -> flag.toString).toMap ++
      privilege.map("privilege" -> _).toMap ++ role.map("role" -> _).toMap
    val security = SecurityContext(
      principal = new Principal {
        def id: PrincipalId = PrincipalId(principalId)
        def attributes: Map[String, String] = principalattributes
      },
      capabilities = capability.map(Capability.apply).toSet,
      level = SecurityContext.Privilege.User.level,
      subjectKind = subjectKind
    )
    val eventengine = EventEngine.noop(DataStore.noop())
    lazy val context: ExecutionContext = ExecutionContext.withSecurityContext(
      ExecutionContext.withRuntimeContext(base, runtime), security
    )
    lazy val uow = new UnitOfWork(context, eventengine, CommitRecorder.noop)
    lazy val interpreter = new UnitOfWorkInterpreter(uow)
    lazy val unitofworkinterpreter = new (UnitOfWorkOp ~> Consequence) {
      def apply[A](operation: UnitOfWorkOp[A]): Consequence[A] =
        Consequence(interpreter.execute(operation))
    }
    lazy val runtime = new RuntimeContext(
      core,
      () => uow,
      unitofworkinterpreter,
      unitofwork => { val _ = unitofwork.commit(); () },
      unitofwork => { val _ = unitofwork.rollback(); () },
      _ => (),
      "supervisor-service-operation-spec"
    )
    context
  }

  def request(requestId: String, action: SupervisorAction = SupervisorAction.Start): SupervisorRequest =
    SupervisorRequest(
      requestId,
      s"key-$requestId",
      "textus-service-spec",
      Some("DEV:service-spec"),
      action,
      "represented-operator",
      Instant.parse("2030-01-01T00:00:00Z")
    )

  def operationRequest(operation: String, record: Record): Request =
    Request.ofService(
      "Supervisor", operation,
      properties = record.fields.map(field => Property(field.key, field.value.single, None)).toList
    )

  final class RecordingSupervisor(delegate: Supervisor) extends Supervisor {
    private var _requests = Vector.empty[SupervisorRequest]
    private var _lookups = Vector.empty[String]
    private var _contexts = Vector.empty[ExecutionContext]

    def requests: Vector[SupervisorRequest] = _requests
    def lookups: Vector[String] = _lookups
    def contexts: Vector[ExecutionContext] = _contexts

    def submit(request: SupervisorRequest)(using context: ExecutionContext): Consequence[SupervisorResult] = {
      _requests = _requests :+ request
      _contexts = _contexts :+ context
      delegate.submit(request)
    }

    def lookup(requestId: String)(using context: ExecutionContext): Consequence[Option[SupervisorResult]] = {
      _lookups = _lookups :+ requestId
      _contexts = _contexts :+ context
      delegate.lookup(requestId)
    }
  }

  final class RecordingProcessHost extends StandaloneSupervisorProcessHost {
    private var _starts = 0
    private var _preflights = 0
    private var _recoveries = 0
    private var _children = Vector.empty[RecordingChild]

    def starts: Int = _starts
    def preflights: Int = _preflights
    def recoveries: Int = _recoveries
    def children: Vector[RecordingChild] = _children

    def preflight(profile: StandaloneLaunchProfile, owned: Option[StandaloneSupervisorChild]): Either[String, Unit] = {
      _preflights += 1
      Right(())
    }

    def start(profile: StandaloneLaunchProfile, instanceId: String): Either[String, StandaloneSupervisorChild] = {
      _starts += 1
      val child = new RecordingChild(instanceId, profile.defaultPort)
      _children = _children :+ child
      Right(child)
    }

    override def recover(profile: StandaloneLaunchProfile, instanceId: String): Either[String, Option[StandaloneSupervisorChild]] = {
      _recoveries += 1
      Left(StandaloneSupervisorProcessHost.RECOVERY_UNAVAILABLE)
    }
  }

  final class RecordingChild(val instanceId: String, val port: Option[Int]) extends StandaloneSupervisorChild {
    private var _alive = true
    private var _stops = 0

    def isAlive: Boolean = _alive
    def stops: Int = _stops
    override def awaitReadiness(): Boolean = true

    def stop(): Boolean = {
      _stops += 1
      _alive = false
      true
    }
  }
}
