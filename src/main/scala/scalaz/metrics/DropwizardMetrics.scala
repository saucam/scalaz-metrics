package scalaz.metrics

import com.codahale.metrics.MetricRegistry.MetricSupplier
import com.codahale.metrics.Timer.Context
import com.codahale.metrics.{Reservoir => DWReservoir, _}
import scalaz.metrics.Label._
import scalaz.metrics.Reservoir._
import scalaz.zio.Task
import scalaz.{Semigroup, Show}

class DropwizardMetrics extends Metrics[Task[?], Context] {

  val registry: MetricRegistry = new MetricRegistry()

  type MetriczIO[A] = Task[A]

  override def counter[L: Show](label: Label[L]): MetriczIO[Long => Task[Unit]] = {
    val lbl = Show[Label[L]].shows(label)
    Task.effect(
      (l: Long) => {
        Task.succeedLazy(registry.counter(lbl).inc(l))
      }
    )
  }

  override def gauge[A, B: Semigroup, L: Show](
    label: Label[L]
  )(
    f: Option[A] => B
  ): MetriczIO[Option[A] => Task[Unit]] = {
    val lbl = Show[Label[L]].shows(label)
    Task.effect(
      (op: Option[A]) =>
        Task.succeedLazy({
          registry.register(lbl, new Gauge[B]() {
            override def getValue: B = f(op)
          })
          ()
        })
    )
  }

  class IOTimer(val ctx: Context) extends Timer[MetriczIO[?], Context] {
    override val a: Context                = ctx
    override def start: MetriczIO[Context] = Task.succeed(a)
    override def stop(io: MetriczIO[Context]): MetriczIO[Double] =
      io.map(c => c.stop().toDouble)
  }

  override def timer[L: Show](label: Label[L]): Task[Timer[MetriczIO[?], Context]] = {
    val lbl = Show[Label[L]].shows(label)
    val iot = Task.succeed(registry.timer(lbl))
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
      case Uniform(config @ _)               => new UniformReservoir
      case ExponentiallyDecaying(config @ _) => new ExponentiallyDecayingReservoir
      case Bounded(window, unit)             => new SlidingTimeWindowReservoir(window, unit)
    }
    val supplier = new MetricSupplier[Histogram] {
      override def newMetric(): Histogram = new Histogram(reservoir)
    }

    Task.effect((a: A) => Task.effect(registry.histogram(lbl, supplier).update(num.toLong(a))))
  }

  override def meter[L: Show](label: Label[L]): MetriczIO[Double => MetriczIO[Unit]] = {
    val lbl = Show[Label[L]].shows(label)
    Task.effect(d => Task.succeed(registry.meter(lbl)).map(m => m.mark(d.toLong)))
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
