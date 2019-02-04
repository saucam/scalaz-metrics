package scalaz.metrics

import java.io.IOException

import com.codahale.metrics.MetricRegistry.MetricSupplier
import com.codahale.metrics.Timer.Context
import com.codahale.metrics.{ Reservoir => DWReservoir, _ }
import scalaz.metrics.Label._
import scalaz.metrics.Reservoir._
import scalaz.zio.IO
import scalaz.{ Semigroup, Show }

class DropwizardMetrics extends Metrics[IO[IOException, ?], Context] {

  val registry: MetricRegistry = new MetricRegistry()

  type MetriczIO[A] = IO[IOException, A]

  override def counter[L: Show](label: Label[L]): MetriczIO[Long => IO[IOException, Unit]] = {
    val lbl = Show[Label[L]].shows(label)
    IO.sync(
      (l: Long) => {
        IO.succeedLazy(registry.counter(lbl).inc(l))
      }
    )
  }

  override def gauge[A, B: Semigroup, L: Show](
    label: Label[L]
  )(
    f: Option[A] => B
  ): MetriczIO[Option[A] => IO[IOException, Unit]] = {
    val lbl = Show[Label[L]].shows(label)
    IO.sync(
      (op: Option[A]) =>
        IO.succeedLazy({
          registry.register(lbl, new Gauge[B]() {
            override def getValue: B = f(op)
          })
          ()
        })
    )
  }

  class IOTimer(val ctx: Context) extends Timer[MetriczIO[?], Context] {
    override val a: Context                = ctx
    override def start: MetriczIO[Context] = IO.succeed(a)
    override def stop(io: MetriczIO[Context]): MetriczIO[Double] =
      io.map(c => c.stop().toDouble)
  }

  override def timer[L: Show](label: Label[L]): IO[IOException, Timer[MetriczIO[?], Context]] = {
    val lbl = Show[Label[L]].shows(label)
    val iot = IO.succeed(registry.timer(lbl))
    val r   = iot.map(t => new IOTimer(t.time()))
    r
  }

  override def histogram[A: Numeric, L: Show](
    label: Label[L],
    res: Reservoir[A]
  )(
    implicit
    num: Numeric[A]
  ): MetriczIO[A => MetriczIO[Unit]] = {
    val lbl = Show[Label[L]].shows(label)
    val reservoir: DWReservoir = res match {
      case Uniform(config@_)               => new UniformReservoir
      case ExponentiallyDecaying(config@_) => new ExponentiallyDecayingReservoir
      case Bounded(window, unit) => new SlidingTimeWindowReservoir(window, unit)
    }
    val supplier = new MetricSupplier[Histogram] {
      override def newMetric(): Histogram = new Histogram(reservoir)
    }

    IO.sync((a: A) => IO.sync(registry.histogram(lbl, supplier).update(num.toLong(a))))
  }

  override def meter[L: Show](label: Label[L]): MetriczIO[Double => MetriczIO[Unit]] = {
    val lbl = Show[Label[L]].shows(label)
    IO.sync(d => IO.succeed(registry.meter(lbl)).map(m => m.mark(d.toLong)))
  }
}

object DropwizardMetrics {
  def makeFilter(filter: Option[String]): MetricFilter = filter match {
    case Some(s) =>
      s.charAt(0) match {
        case '+' => MetricFilter.startsWith(s.substring(1))
        case '-' => MetricFilter.endsWith(s.substring(1))
        case _   => MetricFilter.contains(s)
      }
    case _ => MetricFilter.ALL
  }
}
