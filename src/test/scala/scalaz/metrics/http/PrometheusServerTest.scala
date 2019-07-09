package scalaz.metrics.http

import cats.data.Kleisli
import cats.effect.ExitCode
import org.http4s.implicits._
import org.http4s.server.Router
import org.http4s.server.blaze._
import org.http4s.{Request, Response}
import scalaz.metrics.PrometheusMetrics
import scalaz.metrics.http.DropwizardServerTest.AppEnvironment
import scalaz.zio.interop.catz._
import scalaz.zio.{App, Task, ZIO}
import scalaz.zio.blocking.Blocking
import scalaz.zio.clock.Clock
import scalaz.zio.console.{putStrLn, Console}

import scala.util.Properties.envOrNone

object PrometheusServerTest extends App {
  val port: Int = envOrNone("HTTP_PORT").fold(9090)(_.toInt)
  println(s"Starting server on port $port")

  val metrics = new PrometheusMetrics

  def httpApp[A]: PrometheusMetrics => Kleisli[Task, Request[Task], Response[Task]] =
    (metrics: PrometheusMetrics) =>
      Router(
        "/"        -> StaticService.service,
        "/metrics" -> PrometheusMetricsService.service(metrics),
        "/measure" -> TestMetricsService.service(metrics)
      ).orNotFound

  override def run(args: List[String]): ZIO[AppEnvironment, Nothing, Int] = {
    val server = ZIO.runtime[AppEnvironment].flatMap { implicit rts =>
    BlazeServerBuilder[Task](taskEffectInstances(rts), zioTimer)
      .bindHttp(port)
      .withHttpApp(httpApp(metrics))
      .serve
      .compile[Task, Task, ExitCode]
      .drain
    }
    (for {
      program <- server.provideSome[AppEnvironment] { base =>
        new Clock with Console with Blocking {
          override val console: Console.Service[Any]   = base.console
          override val clock: Clock.Service[Any]       = base.clock
          override val blocking: Blocking.Service[Any] = base.blocking
        }
      }
    } yield program).foldM(err => putStrLn(s"Execution failed with: $err") *> ZIO.succeed(1), _ => ZIO.succeed(0))
  }
}
