package colossus.metrics

import scala.concurrent.duration._

class Rate private[metrics](val address: MetricAddress, pruneEmpty: Boolean)(implicit collection: Collection) extends Collector {

  private val maps = collection.config.intervals.map{i => (i, new CollectionMap[TagMap])}.toMap

  //note - the totals are shared amongst all intervals, and we use the smallest
  //interval to update them
  private val totals = new CollectionMap[TagMap]
  private val minInterval = if (collection.config.intervals.size > 0) collection.config.intervals.min else Duration.Inf

  def hit(tags: TagMap = TagMap.Empty, num: Long = 1) {
    maps.foreach{ case (_, map) => map.increment(tags) }
  }

  def tick(interval: FiniteDuration): MetricMap  = {
    val snap = maps(interval).snapshot(pruneEmpty, true)
    if (interval == minInterval) {
      snap.foreach{ case (tags, value) => totals.increment(tags, value) }
    }
    if (snap.isEmpty) Map() else {
      Map(address -> snap, address / "count" -> totals.snapshot(pruneEmpty, false))
    }
  }
    

}

object Rate {

  def apply(address: MetricAddress, pruneEmpty: Boolean = false)(implicit collection: Collection): Rate = {
    collection.getOrAdd(new Rate(address, pruneEmpty))
  }
}


