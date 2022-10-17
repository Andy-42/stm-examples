package andy42.example

import zio.*
import zio.test.Assertion.*
import zio.test.TestAspect.{timed, ignore}
import zio.test.*

import java.time.temporal.ChronoUnit.MILLIS

object LRUCacheTest extends ZIOSpecDefault:

  // extract state out of LRUCacheLive
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
        expectedSizes = (0, 1, 2, 1)

        actualWatermarks = (watermark0, watermark1, watermark2, watermark3)
        expectedWatermarks = (0L, 0L, 0L, 1L)
      yield assertTrue(actualSizes == expectedSizes) &&
        assertTrue(actualWatermarks == expectedWatermarks)
    }.provide(
      ZLayer.succeed(
        LRUCacheConfig(capacity = 2, fractionToDropOnTrim = 0.25)
      )
    ),
    test("puts from multiple concurrent fibers") {
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
    ),
    test(
      "watermark is moved forward by intervals proportional to configuration"
    ) {
      val n = 100
      val fractionToDropOnTrim = 0.5

      for
        cache <- LRUCache.make[Int, Int]
        cacheImplementation = cache.asInstanceOf[LRUCacheLive[Int, Int]]

        _ <- ZIO.foreach(0 until n) { i =>
          TestClock.adjust(1.millisecond) *> cache.put(i, i)
        }

        watermark <- cache.watermark
        now <- Clock.currentTime(MILLIS)

        intervals = now - watermark
        moveForwardBy = (intervals * fractionToDropOnTrim).toInt

        nextWatermark = cacheImplementation.moveWatermarkForward(watermark, now)
      yield assertTrue(watermark == 0L) &&
        assertTrue(now == 100L) &&
        assertTrue(intervals == 100L) &&
        assertTrue(moveForwardBy == 50) &&
        assertTrue(nextWatermark == watermark + moveForwardBy)
    }.provide(
      ZLayer.succeed(
        LRUCacheConfig(capacity = 100, fractionToDropOnTrim = 0.5)
      )
    ),
    test(
      "watermark will be moved forward by at least one millisecond if there is room"
    ) {
      val n = 100
      val fractionToDropOnTrim = 0.0 //

      for
        cache <- LRUCache.make[Int, Int]
        cacheImplementation = cache.asInstanceOf[LRUCacheLive[Int, Int]]

        _ <- ZIO.foreach(0 until n) { i =>
          TestClock.adjust(1.millisecond) *> cache.put(i, i)
        }

        watermark <- cache.watermark
        now <- Clock.currentTime(MILLIS)

        intervals = now - watermark + 1
        moveForwardBy = (intervals * fractionToDropOnTrim).toLong

        nextWatermark = cacheImplementation.moveWatermarkForward(watermark, now)
      yield assertTrue(watermark == 0L) &&
        assertTrue(now == 100L) &&
        assertTrue(intervals == 101L) &&
        assertTrue(moveForwardBy == 0L) &&
        assertTrue(
          // move forward by at least one since there is room to move
          nextWatermark == watermark + 1
        ) 
    }.provide(
      ZLayer.succeed(
        LRUCacheConfig(capacity = 100, fractionToDropOnTrim = 0)
      )
    ),
    test(
      "watermark will never be moved past now"
    ) {
      val n = 100
      val fractionToDropOnTrim = 1.0 //

      for
        _ <- TestClock.adjust(0.millis)

        cache <- LRUCache.make[Int, Int]
        cacheImplementation = cache.asInstanceOf[LRUCacheLive[Int, Int]]

        _ <- ZIO.foreach(0 until n) { i =>
          cache.put(i, i)
        }

        watermark <- cache.watermark
        now <- Clock.currentTime(MILLIS)

        intervals = now - watermark + 1
        moveForwardBy = (intervals * fractionToDropOnTrim).toLong

        nextWatermark = cacheImplementation.moveWatermarkForward(watermark, now)
      yield assertTrue(watermark == 0L) &&
        assertTrue(now == 0L) &&
        assertTrue(intervals == 1L) &&
        assertTrue(moveForwardBy == 1L) &&
        assertTrue(
          // there is no room to move, so the watermark is not changed
          nextWatermark == watermark
        ) 
    }.provide(
      ZLayer.succeed(
        LRUCacheConfig(capacity = 100, fractionToDropOnTrim = 1.0)
      )
    )
  ) @@ timed
