package scalaz.metrics.http

import cats.effect._
import cats.data.Kleisli
import com.codahale.metrics.jmx.JmxReporter
import org.http4s.server.Router
import org.http4s.{Request, Response}
import scalaz.metrics.DropwizardMetrics
import scalaz.zio.{App, Task, TaskR, ZIO}
import scalaz.zio.interop.catz._
import org.http4s.implicits._
import org.http4s.server.blaze.BlazeServerBuilder
import scalaz.zio.blocking.Blocking
import scalaz.zio.clock.Clock
import scalaz.zio.console.{putStrLn, Console}

import scala.util.Properties.envOrNone

object DropwizardServerTest extends App {
  val port: Int = envOrNone("HTTP_PORT").fold(9090)(_.toInt)
  println(s"Starting server on port $port")

  type AppEnvironment = Clock with Console with Blocking
  type AppTask[A]     = TaskR[AppEnvironment, A]

  val metrics = new DropwizardMetrics

  val reporter: JmxReporter = JmxReporter.forRegistry(metrics.registry).build
  reporter.start()

  def httpApp[A]: DropwizardMetrics => Kleisli[Task, Request[Task], Response[Task]] =
    (metrics: DropwizardMetrics) =>
      Router(
        "/"        -> StaticService.service,
        "/metrics" -> DropwizardMetricsService.service(metrics),
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
