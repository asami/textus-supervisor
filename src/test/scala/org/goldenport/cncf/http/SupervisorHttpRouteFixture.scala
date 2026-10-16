/*
 * @since   Oct. 16, 2026
 * @version Oct. 16, 2026
 */
package org.goldenport.cncf.http

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.comcast.ip4s.{Host, Port}
import org.goldenport.cncf.subsystem.Subsystem
import org.goldenport.cncf.testutil.RuntimeBindingAdmissionFixture
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.syntax.all.*

/** Downstream fixture for the actual generated-operation ingress, not a copied route. */
object SupervisorHttpRouteFixture {
  def withServer[A](subsystem: Subsystem)(run: String => A): A = {
    val admitted = RuntimeBindingAdmissionFixture.admit(subsystem)
    val route = new Http4sHttpServer(new HttpExecutionEngine(admitted))
    EmberServerBuilder.default[IO]
      .withHost(Host.fromString("127.0.0.1").get)
      .withPort(Port.fromInt(0).get)
      .withHttpWebSocketApp(builder => route.routes(builder).orNotFound)
      .build
      .use(server => IO.blocking(run(s"http://127.0.0.1:${server.address.getPort}")))
      .unsafeRunSync()
  }
}
