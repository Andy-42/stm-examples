package andy42.example

import zio.*
import zio.stm.*

import java.time.temporal.ChronoUnit.MILLIS

trait LRUCache[K, V]:
  def get(k: K): UIO[Option[V]]
  def put(k: K, v: V): UIO[Unit]

type AccessTime = Long // milliseconds

case class CacheItem[V](v: V, accessTime: AccessTime)

case class LRUCacheConfig(capacity: Int, fractionToDropOnTrim: Double)

case class LRUCacheLive[K, V](
    config: LRUCacheConfig,
    items: TMap[K, CacheItem[V]],
    watermark: TRef[AccessTime]
) extends LRUCache[K, V]:

  override def get(k: K): UIO[Option[V]] =
    for 
      now <- Clock.currentTime(MILLIS)
      optionItem <- items.updateWith(k)(_.map(_.copy(accessTime = now))).commit
    yield optionItem.map(_.v)

  override def put(k: K, v: V): UIO[Unit] =
    for
      now <- Clock.currentTime(MILLIS)
      _ <- items.put(k, CacheItem(v, now)).commit
      _ <- ZSTM.ifSTM(items.size.map(_ > config.capacity))(removeOldestEntries(now), ZSTM.unit).commit
    yield ()

  def removeOldestEntries(now: AccessTime): USTM[Unit] =
    for
      currentWatermark <- watermark.get
      nextWatermark = moveWatermarkForward(currentWatermark, now)
      _ <- items.removeIfDiscard((_, cacheItem) => cacheItem.accessTime <= nextWatermark)
      _ <- watermark.set(nextWatermark)
    yield ()

  def moveWatermarkForward(watermark: AccessTime, now: AccessTime): AccessTime =
    val intervals = now - watermark + 1
    val moveForwardBy = 1L max (intervals * config.fractionToDropOnTrim).toLong
    now min (watermark + moveForwardBy)

object LRUCache:
  def make[K, V]: URIO[LRUCacheConfig, LRUCache[K, V]] =
    for
      config <- ZIO.service[LRUCacheConfig]
      items <- TMap.empty[K, CacheItem[V]].commit
      now <- Clock.currentTime(MILLIS)
      watermark <- TRef.make(now).commit
    yield LRUCacheLive[K, V](config, items, watermark)
