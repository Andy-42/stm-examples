package andy42.example

import zio.*
import zio.test.Assertion._
import zio.test.TestAspect.timed
import zio.test.*

object LRUCacheTest extends ZIOSpecDefault:

  extension (cache: LRUCache[_, Int])
    def size: UIO[Int] = cache match
      case LRUCacheLive(_, items, _) => items.size.commit

    def watermark: UIO[AccessTime] = cache match
      case LRUCacheLive(_, _, watermark) => watermark.get.commit

    def values: UIO[Set[Int]] = cache match
      case LRUCacheLive(_, items, _) =>
        for cacheItems <- items.values.commit
        yield cacheItems.map(_.v).toSet

  override def spec = suite("LRUCacheTest")(
    test(
      "watermark moves forward and items are removed when size exceeds capacity"
    ) {
      for
        cache <- LRUCache.make[Int, Int]
        size0 <- cache.size
        watermark0 <- cache.watermark

        _ <- cache.put(1, 1)
        size1 <- cache.size
        watermark1 <- cache.watermark

        _ <- TestClock.adjust(1.millisecond)
        _ <- cache.put(2, 2)
        size2 <- cache.size
        watermark2 <- cache.watermark

        _ <- TestClock.adjust(1.millisecond)
        _ <- cache.put(3, 3)
        size3 <- cache.size
        watermark3 <- cache.watermark

        actualSizes = (size0, size1, size2, size3)
        expectedSizes = (0, 1, 2, 2)

        actualWatermarks = (watermark0, watermark1, watermark2, watermark3)
        expectedWatermarks = (-1L, -1L, -1L, 0L)
      yield assertTrue(actualSizes == expectedSizes) &&
        assertTrue(actualWatermarks == expectedWatermarks)
    }.provide(
      ZLayer.succeed(
        LRUCacheConfig(capacity = 2, fractionToDropOnTrim = 0.25)
      )
    ) @@ timed,
    test("Correctly updates from multiple concurrent fibers") {
      for
        cache <- LRUCache.make[String, Int]

        n = 10000

        _ <- TestClock.adjust(1.millisecond) // Avoid warning

        fiber0 <- ZIO
          .foreach(1 until n by 2)(i => cache.put(i.toString, i))
          .fork
        fiber1 <- ZIO
          .foreach(0 until n by 2)(i => cache.put(i.toString, i))
          .fork
        fiber2 <- ZIO
          .foreach(0 until n by 3)(i => cache.put(i.toString, i))
          .fork
          
        _ <- fiber0.join *> fiber1.join *> fiber2.join

        actualSize <- cache.size
        expectedSize = n

        actualValues <- cache.values
        expectedValues = (0 until n).toSet
      yield assertTrue(actualSize == expectedSize) &&
        assertTrue(actualValues == expectedValues)
    }.provide(
      ZLayer.succeed(
        LRUCacheConfig(capacity = 10000, fractionToDropOnTrim = 0.25)
      )
    )
  ) @@ timed
