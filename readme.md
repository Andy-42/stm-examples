# Software Transactional Memory - Simple Examples

This repository is a collection of _simple_ examples of using Software Transactional Memory.

In the past, I would not have dreamed of implementing a lock-free concurrent data structure myself. I would most likely have used an existing JDK implementation. If you are unclear on why, have a look at the ~6000 lines of code in `java.util.concurrent.ConcurrentHashMap`.

For many applications, these concurrent data structures (from the JDK) are usable, even if they don't have the most ergonomic APIs - especially for a Scala developer. The more fundamental problem is that it is difficult to compose concurrent data structures.

ZIO STM changes all that. Not only can we compose concurrent data structures in useful ways. Composing concurrent data structures within a transaction opens a whole new realm of possiblities.

These examples are implemented using Scala 3 and ZIO 2.

## LRUCache

This example was inspired by the article [How to write a (completely lock-free) concurrent LRU Cache with ZIO STM](https://scalac.io/blog/how-to-write-a-completely-lock-free-concurrent-lru-cache-with-zio-stm/) by Jorge Vasquez.
This article has some excellent background information on ZIO and the motivation for using ZIO STM. 
I was able to use this implementation directly in my project almost no change. 

The main feature of this implementation is that the entire cache is composed of a doubly-linked list, with the most recently referenced item in the cache at the head of the list, and the least recently used at the tail. 
If the cache reaches capacity, the item at the tail of this list is dropped.

### Structure

- implemented in a form analogous to a ZIO Layer (but it isn't).

### Potential Performance Issues?

From a performance perspective, the management of the linked list might be an issue.
Each time we `get` an item from the cache, the transaction includes:
1. Remove the item from where it appears in the linked list (change the preceding and following items to point to each other).
2. Add the item to the head of the list (change the item so that its link to the next item points to the existing the head of the list, change the `startRef` to point to the item).
3. Accessing items in the map (the previous and next for the item being retrieved, as well as the start item) requires a lookup in `items`. Each one of those map lookups adds a step to the transaction journal.


- Just a `get` operation on the cache requires a significant number of steps in the transaction journal (see above). Having fewer steps is preferred.
- The `startRef` is going to be changed on every access, so the `startRef` might be a hot-spot that causes more transaction retries that we would like.
- Each `CacheItem` has `left` and `right` fields that are each an `Option[K]`, which means two extra objects in the heap for every cache entry. My application would fill the JVM heap with cache instances so this is a significant number of extra heap objects that could have an impact on garbage collection performance.

### Don't Manage a Linked List at All!

The biggest problem seems to be the management of the doubly-linked list. The original implementation maintains this list _accurately_. An LRUCache doesn't necessarily need this level of accuracy to be effective - it only requires that more recently used items are retained in the cache, and less recently used items can be discarded.

In this implementation, each `CacheItem` has an `accessTime` field that is set to the current system time for each `get` or `put` operation. This accounts for much of the simplification relative to the previous example.

When we `put` an item into the cache, the number of items in the cache can grow larger than our desired capacity, so we have to remove some older items from the cache. In this case, we use `ZSTM.ifSTM` to test whether `items` has execeed capacity, and if so, remove some older entries from the cache.
- The entries are not removed one-at-a-time, but a number of cache entries are removed all in one go using the `TRef.removeIfDiscard` method.
- We will always want to remove more than a single entry each time, so that the cache is normally at below capacity. This means that for most `get` operations we do a quick test on the size of `items` and then do nothing.
- Since the purging of older items takes place in a separate STM transaction, this could be done on a forked fiber so that any purging overhead does not impact the fiber that called the `put` operation.

The algorithm for removing the oldest entries is very much an approximation. It makes some assumptions about the distribution of cache entries over time, and than removing some fraction of the time range will remove a similar fraction of entries. A single use of `removeOldestEntries` might not actually remove any entries at all, or it could remove everything from the cache!

### Simple

While the original LRUCache implementation was quite straightforward, the take on it that I have shown here is even simpler (less than 60 lines of code).

We are working at a higher level of abstraction, and it is now simple enough to

small number of lines of code.

### Why is this important?

This new design for an LRU Cache is not fully developed. Before using this in an application, you should do the performance testing to understand how it performs within your application. At the very least, I think it is plausible that this implementation could perform better in my application.

That is the point: you aren't limited to a small number concurrent data structures with specific functionality. STM makes it possible to try out different approaches to the problem. You 