package andy42.example

import zio.*
import zio.stm.*
import java.sql.SQLException

trait CachedDataService:
  def get(k: String): IO[SQLException, List[Int]]
  def append(k: String, e: Int): IO[SQLException, List[Int]]

case class CachedDataServiceLive(
    inFlight: TSet[String],
    cache: LRUCache[String, List[Int]],
    dataService: DataService
) extends CachedDataService:
  override def get(k: String): IO[SQLException, List[Int]] =
    ZIO.scoped {
      for
        _ <- withPermit(k)

        maybeCacheItem <- cache.get(k)
        item <- maybeCacheItem.fold(
          for
            x <- dataService.get(k)
            _ <- cache.put(k, x)
          yield x
        )(ZIO.succeed)
      yield item
    }

  override def append(k: String, e: Int): IO[SQLException, List[Int]] =
    ZIO.scoped {
      for
        _ <- withPermit(k)

        maybeCacheItem <- cache.get(k)
        item <- maybeCacheItem.fold(dataService.get(k))(ZIO.succeed)
        nextItem = item :+ e
        _ <- dataService.put(k, nextItem)
        _ <- cache.put(k, nextItem)
      yield nextItem
    }

  private def acquirePermit(k: String): UIO[String] =
    STM
      .ifSTM(inFlight.contains(k))(STM.retry, inFlight.put(k).map(_ => k))
      .commit

  private def releasePermit(k: String): UIO[Unit] =
    inFlight.delete(k).commit

  private def withPermit(k: String): ZIO[Scope, Nothing, String] =
    ZIO.acquireRelease(acquirePermit(k))(releasePermit(_))

object CachedDataService:
  val layer: URLayer[LRUCacheConfig & DataService, CachedDataService] =
    ZLayer {
      for
        inFlight <- TSet.empty[String].commit
        cache <- LRUCache.make[String, List[Int]]
        dataService <- ZIO.service[DataService]
      yield CachedDataServiceLive(inFlight, cache, dataService)
    }
