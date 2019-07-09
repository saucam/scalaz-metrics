package scalaz.metrics.http

import cats.data.Kleisli
import org.http4s.{Request, Response}
import org.http4s.server.Router
import scalaz.metrics.DropwizardMetrics
import scalaz.zio._
import scalaz.zio.interop.catz._
import org.http4s.implicits._
import scalaz.zio.clock.Clock

import scala.util.Properties.envOrNone

object Server {
  val port: Int = envOrNone("HTTP_PORT").fold(9090)(_.toInt)

  implicit val clock: Clock = Clock.Live
  type MetricsTask[A]     = TaskR[Any, A]

  val httpApp: DropwizardMetrics => Kleisli[Task, Request[Task], Response[Task]] = (metrics: DropwizardMetrics) =>
    Router(
      "/"        -> StaticService.service,
      "/metrics" -> DropwizardMetricsService.service(metrics)
    ).orNotFound

  /* val builder: DropwizardMetrics => Task[Unit] = (metrics: DropwizardMetrics) =>
    BlazeServerBuilder[Task]
      .bindHttp(port)
      .withHttpApp(httpApp(metrics))
      .serve
      .compile
      .drain */
}
