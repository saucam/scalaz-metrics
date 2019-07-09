package scalaz.metrics

import scalaz.Scalaz._
import scalaz.zio.{App, Task, ZIO}

import scala.math.Numeric.IntIsIntegral

object DropwizardMetricsSpec extends App {

  val dropwizardMetrics = new DropwizardMetrics

  val tester: Option[Unit] => Long = (op: Option[Unit]) => System.nanoTime()

  def performTests: Task[Unit] =
    for {
      f  <- dropwizardMetrics.counter(Label(Array("test", "counter")))
      _  <- f(1)
      _  <- f(2)
      g  <- dropwizardMetrics.gauge(Label(Array("test", "gauge")))(tester)
      _  <- g(None)
      t  <- dropwizardMetrics.timer(Label(Array("test", "timer")))
      t1 = t.start
      l <- Task.foreach(
            List(
              Thread.sleep(1000L),
              Thread.sleep(1400L),
              Thread.sleep(1200L)
            )
          )(a => t.stop(t1))
      h <- dropwizardMetrics.histogram(Label(Array("test", "histogram")))
      _ <- Task.foreach(List(h(10), h(25), h(50), h(57), h(19)))(_.unit)
      m <- dropwizardMetrics.meter(Label(Array("test", "meter")))
      _ <- Task.foreach(1 to 5)(i => Task.succeed(m(1)))
    } yield { println(s"time $l ns"); () }

  def run(args: List[String]): ZIO[Environment, Nothing, Int] =
    performTests.run
      .map(ei => {
        printMetrics()
        ei.fold(_ => 1, _ => 0)
      })

  def printMetrics(): Unit = {
    println(
      dropwizardMetrics.registry
        .getCounters()
        .get("test.counter")
        .getCount
    )
    println(
      dropwizardMetrics.registry
        .getGauges()
        .get("test.gauge")
        .getValue
    )
    println(
      dropwizardMetrics.registry
        .getTimers()
        .get("test.timer")
        .getCount
    )
    println(
      dropwizardMetrics.registry
        .getTimers()
        .get("test.timer")
        .getMeanRate
    )
    println(
      dropwizardMetrics.registry
        .getHistograms()
        .get("test.histogram")
        .getSnapshot
        .get75thPercentile
    )
    println(
      dropwizardMetrics.registry
        .getHistograms()
        .get("test.histogram")
        .getSnapshot
        .get99thPercentile
    )
    println(
      dropwizardMetrics.registry
        .getHistograms()
        .get("test.histogram")
        .getSnapshot
        .getMean
    )
    println(
      dropwizardMetrics.registry
        .getMeters()
        .get("test.meter")
        .getMeanRate
    )
    println(
      dropwizardMetrics.registry
        .getMeters()
        .get("test.meter")
        .getCount
    )
  }
}
