package scalaz.metrics

import io.prometheus.client._
import scalaz.metrics.Label._
import scalaz.metrics.Reservoir.{ Bounded, Config, ExponentiallyDecaying, Uniform }
import scalaz.zio.Task
import scalaz.{ Semigroup, Show }

class PrometheusMetrics extends Metrics[Task[?], Summary.Timer] {

  val registry: CollectorRegistry = CollectorRegistry.defaultRegistry

  type MetriczIO[A] = Task[A]

  override def counter[L: Show](label: Label[L]): MetriczIO[Long => Task[Unit]] = {
    val lbl = Show[Label[L]].shows(label)
    val c = Counter
      .build()
      .name(lbl)
      .help(s"$lbl counter")
      .register()
    Task.effect { l: Long =>
      Task.succeedLazy(c.inc(l.toDouble))
    }
  }

  override def gauge[A, B: Semigroup, L: Show](label: Label[L])(
    f: Option[A] => B
  ): MetriczIO[Option[A] => Task[Unit]] = {
    val lbl = Show[Label[L]].shows(label)
    val g = Gauge
      .build()
      .name(lbl)
      .help(s"$lbl gauge")
      .register()
    Task.effect(
      (op: Option[A]) =>
        Task.succeedLazy(f(op) match {
          case l: Long   => g.inc(l.toDouble)
          case d: Double => g.inc(d)
          case _         => ()
        })
    )
  }

  override def histogram[A: scala.Numeric, L: Show](label: Label[L], res: Reservoir[A])(
    implicit num: scala.Numeric[A]
  ): MetriczIO[A => MetriczIO[Unit]] = {
    val lbl = Show[Label[L]].shows(label)
    val h = Histogram
      .build()
      .name(lbl)
      .help(s"$lbl histogram")
      .register()
    Task.effect((a: A) => Task.effect(h.observe(num.toDouble(a))))
  }

  def processConfig(config: Option[Config], values: Tuple3[String, String, String]): Tuple3[Double, Double, Int] =
    config match {
      case None => (1.0, 1.0, 1)
      case Some(m) =>
        val d1 = m.getOrElse(values._1, DoubleZ(1.0)) match {
          case DoubleZ(d) => d
          case _          => 1.0
        }

        val d2: Double = m.getOrElse(values._2, DoubleZ(1.0)) match {
          case DoubleZ(d) => d
          case _          => 1.0
        }

        val i1: Int = m.getOrElse(values._3, IntegerZ(1)) match {
          case IntegerZ(i) => i
          case _           => 1
        }
        (d1, d2, i1)
    }

  def histogramTimer[A: scala.Numeric, L: Show](
    label: Label[L],
    res: Reservoir[A] = Reservoir.ExponentiallyDecaying(None)
  ): MetriczIO[() => MetriczIO[Unit]] = {
    val lbl = Show[Label[L]].shows(label)
    val hb = Histogram
      .build()
      .name(lbl)
      .help(s"$lbl histogram")

    val builder = res match {
      case Uniform(config) =>
        val c = processConfig(config, ("start", "width", "count"))
        hb.linearBuckets(c._1, c._2, c._3)
      case ExponentiallyDecaying(config) =>
        val c = processConfig(config, ("start", "factor", "count"))
        hb.exponentialBuckets(c._1, c._2, c._3)
      case Bounded(window @ _, unit @ _) => hb
    }

    val h = builder.register()

    Task.effect({
      val timer = h.startTimer()
      () =>
        Task.effect({
          timer.observeDuration()
          ()
        })
    })
  }

  type SummaryTimer = Summary.Timer

  class IOTimer(val ctx: SummaryTimer) extends Timer[MetriczIO[?], SummaryTimer] {
    override val a: SummaryTimer                = ctx
    override def start: MetriczIO[SummaryTimer] = Task.succeed({ println("start"); a })
    override def stop(io: MetriczIO[SummaryTimer]): MetriczIO[Double] =
      io.map(c => { println("stop"); c.observeDuration() })
  }

  override def timer[L: Show](label: Label[L]): Task[Timer[MetriczIO[?], SummaryTimer]] = {
    val lbl = Show[Label[L]].shows(label)
    val iot = Task.succeed(
      Summary
        .build()
        .name(lbl)
        .help(s"$lbl timer")
        .register()
    )
    val r = iot.map(s => new IOTimer(s.startTimer()))
    r
  }

  override def meter[L: Show](label: Label[L]): Task[Double => Task[Unit]] = {
    val lbl = Show[Label[L]].shows(label)
    val iot = Task.succeed(
      Summary
        .build()
        .name(lbl)
        .help(s"$lbl timer")
        .register()
    )
    Task.effect((d: Double) => iot.map(s => s.observe(d)))
  }

}

object PrometheusMetrics {
  implicit def DoubleSemigroup: Semigroup[Double] = Semigroup.instance((a, b) => a + b)
}
