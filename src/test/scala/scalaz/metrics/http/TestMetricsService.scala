package scalaz.metrics.http

import java.io.IOException

import com.codahale.metrics.Timer.Context
import org.http4s.dsl.impl.Root
import org.http4s.dsl.io._
import org.http4s.{ HttpRoutes, Response }
import scalaz.Scalaz._
import scalaz.metrics.{ Label, Metrics }
import scalaz.zio.{ IO, RTS }
import scalaz.zio.interop.Task
import scalaz.zio.interop.catz._

import scala.math.Numeric.IntIsIntegral

object TestMetricsService extends RTS {
  println("Serving")

  val s: Stream[Int]               = Stream.from(1)
  val tester: Option[Unit] => Long = (op: Option[Unit]) => s.takeWhile(_ < 10).head.toLong

  def performTests(metrics: Metrics[IO[IOException, ?], Context]): IO[IOException, String] =
    for {
      f <- metrics.counter(Label(Array("test", "counter")))
      _ <- f(1)
      _ <- f(2)
      _ <- metrics.gauge(Label(Array("test", "gauge")))(tester)
      t <- metrics.timer(Label(Array("test", "timer")))
      l <- IO.foreach(
            List(
              Thread.sleep(1000L),
              Thread.sleep(1400L),
              Thread.sleep(1200L)
            )
          )(a => t.stop(t.apply))
      h <- metrics.histogram(Label(Array("test", "histogram")))
      _ <- IO.foreach(List(h(10), h(25), h(50), h(57), h(19)))(_.void)
      m <- metrics.meter(Label(Array("test", "meter")))
      _ <- IO.foreach(1 to 5)(i => IO.succeed(m(1)))
    } yield { s"time $l ns" }

  val service = (metrics: Metrics[IO[IOException, ?], Context]) =>
    HttpRoutes.of[Task] {
      case GET -> Root =>
        val m = performTests(metrics).attempt
          .map(ei => {
            ei.fold(_ => "failure encountered", s => s)
          })

        Task(Response[Task](Ok).withEntity(unsafeRun(m)))
  }
}
