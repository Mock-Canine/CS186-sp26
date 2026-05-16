# ConcatBacktrackingIterator Q&A

## Q1. What is the point of the empty back iterator? Explain the usage of `ConcatBacktrackingIterator` with an example.

`EmptyBacktrackingIterator` is a "null object" iterator: it represents "there are no items" while still satisfying the `BacktrackingIterator<T>` interface.

In `EmptyBacktrackingIterator.java`:

```java
hasNext() -> false
next() -> throws NoSuchElementException
markPrev() / markNext() / reset() -> do nothing
```

The point is convenience and safety. For example, `ConcatBacktrackingIterator` starts with:

```java
this.nextItemIterator = new EmptyBacktrackingIterator<>();
```

That means `nextItemIterator` is never `null`, so `hasNext()` can safely call:

```java
if (!this.nextItemIterator.hasNext()) this.moveNextToNonEmpty();
```

without checking for `null`.

`ConcatBacktrackingIterator<T>` flattens multiple `BacktrackingIterable<T>` objects into one long `BacktrackingIterator<T>`.

Example:

```text
outer iterator yields these iterables:

[1, 2, 3]
[]
[4, 5, 6]
[7, 8]
```

Then `ConcatBacktrackingIterator<Integer>` behaves like one iterator over:

```text
1, 2, 3, 4, 5, 6, 7, 8
```

The empty iterable is skipped automatically by `moveNextToNonEmpty()`.

A real use in this project is `Table.ridIterator()`:

```java
BacktrackingIterator<Page> iter = pageDirectory.iterator();
return new ConcatBacktrackingIterator<>(new PageIterator(iter, false));
```

Here the outer iterator walks pages. Each page becomes an iterable of `RecordId`s on that page. `ConcatBacktrackingIterator` turns:

```text
page 1 record IDs: [rid1, rid2]
page 2 record IDs: []
page 3 record IDs: [rid3, rid4]
```

into:

```text
[rid1, rid2, rid3, rid4]
```

So the caller gets one clean full-table scan iterator instead of manually nesting loops over pages and records.

Backtracking still works because `markPrev()` and `markNext()` delegate to the current inner iterator and remember which inner iterable/page the mark came from. Then `reset()` restores `nextItemIterator` back to that marked inner iterator.

## Q2. Explain the methods in the `ConcatBacktrackingIterator` class in detail.

The class idea:

`ConcatBacktrackingIterator<T>` takes an iterator of `BacktrackingIterable<T>` objects and exposes them as one flat `BacktrackingIterator<T>`.

Instead of this shape:

```text
outerIterator -> [ [1, 2, 3], [], [4, 5], [6] ]
```

it gives the caller this shape:

```text
1, 2, 3, 4, 5, 6
```

Important fields:

```java
private BacktrackingIterator<BacktrackingIterable<T>> outerIterator;
private List<BacktrackingIterable<T>> iterables;
private BacktrackingIterator<T> prevItemIterator;
private BacktrackingIterator<T> nextItemIterator;
private BacktrackingIterator<T> markItemIterator;

private int prevIndex = -1;
private int nextIndex = -1;
private int markIndex = -1;
```

`outerIterator` is the iterator over chunks. Each chunk is a `BacktrackingIterable<T>`.

`iterables` stores chunks already discovered from `outerIterator`. This matters because if you reset backward, the class may need to recreate or revisit an inner iterator.

`prevItemIterator` is the inner iterator that produced the most recent item.

`nextItemIterator` is the inner iterator that will produce the next item.

`markItemIterator` is the inner iterator containing the current mark.

The index fields track which chunk those iterators belong to. `-1` means "none yet."

### Constructor

```java
public ConcatBacktrackingIterator(
    BacktrackingIterator<BacktrackingIterable<T>> outerIterator
)
```

The constructor stores the outer iterator and initializes state:

```java
this.iterables = new ArrayList<>();
this.outerIterator = outerIterator;
this.prevItemIterator = null;
this.nextItemIterator = new EmptyBacktrackingIterator<>();
this.markItemIterator = null;
```

The key detail is:

```java
this.nextItemIterator = new EmptyBacktrackingIterator<>();
```

At construction time, we have not loaded the first real inner iterator yet. Instead of using `null`, the class uses an empty iterator. That makes the rest of the code simpler because it can safely call:

```java
this.nextItemIterator.hasNext()
```

without a null check.

### `moveNextToNonEmpty()`

```java
private void moveNextToNonEmpty()
```

This helper advances `nextItemIterator` until it points at an inner iterator with data, or until there are no more chunks.

```java
while (!this.nextItemIterator.hasNext()) {
```

As long as the current inner iterator is empty or exhausted, keep looking.

```java
if (nextIndex + 1 < iterables.size()) {
    nextIndex++;
    this.nextItemIterator = iterables.get(nextIndex).iterator();
}
```

If the next chunk has already been loaded into `iterables`, move to it and create its iterator.

```java
else {
    assert(nextIndex + 1 == iterables.size());
    if (!outerIterator.hasNext()) break;
    iterables.add(outerIterator.next());
}
```

Otherwise, if we have not loaded the next chunk yet, pull one more chunk from `outerIterator` and store it in `iterables`.

Notice that after adding a new iterable, the loop repeats. On the next loop iteration, `nextIndex + 1 < iterables.size()` will be true, so it will create an iterator for the newly added chunk.

### `hasNext()`

```java
@Override
public boolean hasNext() {
    if (!this.nextItemIterator.hasNext()) this.moveNextToNonEmpty();
    return this.nextItemIterator.hasNext();
}
```

This checks whether the concatenated iterator has another item.

First it checks the current inner iterator. If that iterator is empty or exhausted, it calls `moveNextToNonEmpty()` to find the next non-empty inner iterator.

Then it returns whether the final `nextItemIterator` has another item.

### `next()`

```java
@Override
public T next() {
    if (!hasNext()) throw new NoSuchElementException();
    T item = this.nextItemIterator.next();
    this.prevItemIterator = this.nextItemIterator;
    prevIndex = nextIndex;
    return item;
}
```

This returns the next item in the flattened sequence.

First it makes sure there is actually a next item. This call may also advance `nextItemIterator` to the next non-empty chunk.

Then it gets the item from the current inner iterator.

Finally, it records where the returned item came from. This is needed for `markPrev()`.

### `markPrev()`

```java
@Override
public void markPrev() {
    if (prevIndex == -1) {
        return;
    }
    this.markItemIterator = this.prevItemIterator;
    this.markItemIterator.markPrev();
    markIndex = prevIndex;
}
```

`markPrev()` marks the last value returned by `next()`.

If no item has been returned yet, `prevIndex` is `-1`, so it does nothing.

Otherwise, it records the inner iterator that produced the previous item as the marked iterator, asks that inner iterator to mark its own previous item, and remembers which chunk contains the mark.

Example:

```text
chunks: [1,2], [3,4]

next() -> 1
next() -> 2
markPrev()
next() -> 3
reset()
next() -> 2
```

The mark was inside the first inner iterator, at value `2`.

### `markNext()`

```java
@Override
public void markNext() {
    if (!hasNext()) return;
    this.markItemIterator = this.nextItemIterator;
    this.markItemIterator.markNext();
    markIndex = nextIndex;
}
```

`markNext()` marks the value that would be returned by the next call to `next()`.

First it calls `hasNext()`. If there is no next item, there is nothing to mark. This call may move `nextItemIterator` forward to the next non-empty inner iterator.

Then it records the current inner iterator as the marked iterator and tells it to mark its next value.

Example:

```text
chunks: [1,2], [], [3,4]

next() -> 1
next() -> 2
markNext()
next() -> 3
next() -> 4
reset()
next() -> 3
```

Even though there was an empty chunk between `[1,2]` and `[3,4]`, `markNext()` calls `hasNext()`, which moves to `[3,4]` first. So the mark correctly points to `3`.

### `reset()`

```java
@Override
public void reset() {
    if (markIndex == -1) {
        return;
    }

    prevItemIterator = null;
    prevIndex = -1;

    this.nextItemIterator = this.markItemIterator;
    this.nextItemIterator.reset();
    nextIndex = markIndex;
}
```

`reset()` moves the concatenated iterator back to the last mark.

If nothing has been marked, it does nothing.

After a reset, there is no valid "previous item" yet. You must call `next()` again before `markPrev()` can do anything meaningful.

Then the class restores `nextItemIterator` to the inner iterator that contains the mark, resets that inner iterator to its own marked position, and restores `nextIndex`.

Example with `markPrev()`:

```text
chunks: [1,2], [3,4]

next()      -> 1
next()      -> 2
markPrev()  // marks 2
next()      -> 3
next()      -> 4
reset()
next()      -> 2
next()      -> 3
```

Example with `markNext()`:

```text
chunks: [1,2], [3,4]

next()      -> 1
markNext()  // marks 2
next()      -> 2
next()      -> 3
reset()
next()      -> 2
```

One subtle thing: `reset()` only resets the inner iterator where the mark was. It does not reset the whole `outerIterator`. That is why `iterables` stores the chunks already seen. The class can continue from the marked chunk and then move forward through already loaded chunks or lazily load more chunks later.

## Q3. What is the point of maintaining the three indexes? They seem only useful in `moveNextToNonEmpty()`.

The three indexes exist because the class has three different positions to remember:

```java
prevIndex
nextIndex
markIndex
```

They correspond to these iterator references:

```java
prevItemIterator  // iterator that returned the previous item
nextItemIterator  // iterator that will return the next item
markItemIterator  // iterator containing the marked item
```

So the indexes answer: which inner iterable did that iterator come from?

`nextIndex` is the most visibly useful one because `moveNextToNonEmpty()` uses it to know which inner iterable to advance to next:

```java
if (nextIndex + 1 < iterables.size()) {
    nextIndex++;
    this.nextItemIterator = iterables.get(nextIndex).iterator();
}
```

But `prevIndex` and `markIndex` matter for backtracking.

`prevIndex` tracks which inner iterator produced the last returned item. `markPrev()` uses it:

```java
if (prevIndex == -1) return;

this.markItemIterator = this.prevItemIterator;
this.markItemIterator.markPrev();
markIndex = prevIndex;
```

The `prevIndex == -1` check tells the class: "there is no previous item worth marking." That is true before any `next()` call, and also after `reset()`.

`markIndex` tracks which inner iterable contains the marked item. In `reset()`:

```java
this.nextItemIterator = this.markItemIterator;
this.nextItemIterator.reset();
nextIndex = markIndex;
```

After reset, the "next item" position is back at the marked inner iterator. So `nextIndex` must also move back.

Suppose:

```text
inner iterators:
0: [1, 2]
1: [3, 4]
2: [5, 6]
```

Then:

```text
next() -> 1
next() -> 2
markPrev()   // mark is in inner iterator 0
next() -> 3
next() -> 4  // now nextIndex is 1
reset()      // go back to marked item 2
```

After `reset()`, `nextItemIterator` points back to inner iterator `0`, so `nextIndex` must become `0` again. Otherwise the next time `moveNextToNonEmpty()` runs, it would think it is still at iterator `1` and might skip iterator `1` or advance incorrectly.

So the indexes mostly show their value when moving between inner iterators, but `prevIndex` and `markIndex` are what make movement after `markPrev()` / `markNext()` / `reset()` coherent.

## Q4. If we delete the `iterables` field, the three index fields are useless. Will the class behave normally?

If you delete `iterables`, the three indexes lose most of their purpose. But the class will not behave normally unless you also redesign how reset works.

`iterables` is not just storage for `moveNextToNonEmpty()`. It is a cache of inner iterables that have already been discovered from `outerIterator`.

That cache matters because `outerIterator` only moves forward. Once this class pulls an inner iterable from `outerIterator`, without saving it, that inner iterable is gone from the outer stream.

Example:

```text
outerIterator yields:
0: [1, 2]
1: [3, 4]
2: [5, 6]
```

Suppose:

```text
next()      -> 1
next()      -> 2
markPrev()  // mark 2, inside chunk 0
next()      -> 3
next()      -> 4
next()      -> 5
reset()
next()      -> 2
```

After reset, we are back inside chunk `0`.

Now the following sequence should still be reachable:

```text
2, 3, 4, 5, 6
```

To do that, after finishing chunk `0`, the concat iterator must be able to move again to chunk `1`, then chunk `2`.

That is why it keeps:

```java
private List<BacktrackingIterable<T>> iterables;
```

When chunk `1` and chunk `2` were first loaded, they were saved in `iterables`. So after resetting backward to chunk `0`, the iterator can replay forward through already-seen chunks.

Without `iterables`, after resetting to chunk `0`, the `outerIterator` might already be past chunk `1` and chunk `2`, maybe even at the end. Then after returning `2`, the concat iterator would not know how to get back to `[3, 4]`. It would skip data or stop too early.

So:

```text
Without iterables:
next() -> 1
next() -> 2
markPrev()
next() -> 3
next() -> 4
next() -> 5
reset()
next() -> 2
hasNext() -> maybe false  // wrong, because 3,4,5,6 should still be reachable
```

The indexes exist because of this cached list:

```java
prevIndex
nextIndex
markIndex
```

They tell the class where each important inner iterator lives inside `iterables`.

Removing `iterables` breaks the backtracking guarantee of `ConcatBacktrackingIterator`. You could remove `iterables` only if this were a plain one-pass concat iterator with no meaningful `reset()` support. But since this class implements `BacktrackingIterator`, it needs the cache.

## Q5. The usage of `outerIterator` is weird. It is claimed to be a `BacktrackingIterator`, but the class only invokes `hasNext()` and `next()`. It does not use its backtrack property.

That observation is correct. In this implementation, `outerIterator` is typed as:

```java
BacktrackingIterator<BacktrackingIterable<T>> outerIterator
```

but `ConcatBacktrackingIterator` only calls:

```java
outerIterator.hasNext()
outerIterator.next()
```

It never calls:

```java
outerIterator.markPrev()
outerIterator.markNext()
outerIterator.reset()
```

So this class does not use the outer iterator's backtracking ability directly.

The reason it still takes a `BacktrackingIterator` is mostly type-system convenience and consistency with the rest of the codebase. The outer iterator is usually produced by existing code that already returns `BacktrackingIterator`s.

For example, in `Table.ridIterator()`:

```java
BacktrackingIterator<Page> iter = pageDirectory.iterator();
return new ConcatBacktrackingIterator<>(new PageIterator(iter, false));
```

`PageIterator` itself implements:

```java
BacktrackingIterator<BacktrackingIterable<RecordId>>
```

So it fits the constructor.

But internally, `ConcatBacktrackingIterator` handles backtracking by caching the inner `BacktrackingIterable`s and by marking/resetting the current inner iterator. It does not rewind `outerIterator`.

Conceptually, the constructor could have been weaker:

```java
public ConcatBacktrackingIterator(Iterator<BacktrackingIterable<T>> outerIterator)
```

because `hasNext()` and `next()` are the only required outer operations.

So the mental model is:

```text
outerIterator backtracking: not used
inner iterators backtracking: essential
iterables cache: essential for replaying after reset
```

The outer iterator just supplies chunks lazily. The real backtracking logic happens inside the flattened stream.

## Q6. Why can we not use the `outerIterator`'s rewind method for replaying? The current method has overhead from maintaining more fields and writing more code.

You could design a concat iterator that uses `outerIterator.reset()`, but it would not be simpler. It would need extra state anyway, and it would be less robust.

Example:

```text
outerIterator yields chunks:

chunk 0: [1, 2, 3]
chunk 1: [4, 5]
chunk 2: [6, 7]
```

Now run:

```text
next()      -> 1
next()      -> 2
markPrev()  // mark value 2
next()      -> 3
next()      -> 4
next()      -> 5
reset()
```

After `reset()`, the next sequence should be:

```text
2, 3, 4, 5, 6, 7
```

The mark is not "at chunk 0." It is inside chunk 0, specifically at value `2`.

If you only rewind `outerIterator`, the best it can do is return you to some outer chunk:

```text
chunk 0: [1, 2, 3]
```

But then you still need to know: should replay start at `1`, `2`, or `3`?

So using outer rewind alone is not enough. You also need inner state.

Maybe you use both:

```text
outerIterator.reset() -> go back to chunk 0
innerIterator.reset() -> go back to value 2
```

But when you reset the outer iterator, you probably recreate a new inner iterator for chunk 0:

```java
BacktrackingIterable<T> chunk = outerIterator.next();
BacktrackingIterator<T> inner = chunk.iterator();
```

That new `inner` does not necessarily remember the old mark at value `2`. The old mark was stored in the old inner iterator object.

That is why the current code keeps:

```java
markItemIterator
```

It points directly to the inner iterator that knows where the mark is.

Now suppose after reset you use the old marked inner iterator:

```text
reset()
next() -> 2
next() -> 3
```

Now chunk 0 is exhausted. What comes next?

Correct answer:

```text
4, 5, 6, 7
```

But if you deleted `iterables`, the concat iterator no longer has cached access to chunk 1 and chunk 2. It must somehow use `outerIterator` again.

If `outerIterator` has already advanced past chunk 1 and chunk 2, then it may be at the end:

```text
outerIterator state: after chunk 2
```

So after replaying `2, 3`, the iterator would incorrectly think there is nothing left.

You might try to solve that by calling `outerIterator.reset()` too. But then you need to coordinate:

```text
reset outer to chunk 0
skip chunk 0 because we already replayed it with old inner iterator
then continue with chunk 1
```

That requires more state, for example:

```text
which chunk contained the mark?
have we already consumed the marked chunk after reset?
how many outer chunks should be skipped?
where should outer be marked?
```

That is basically the same kind of bookkeeping as `nextIndex`, `markIndex`, and `iterables`, just in a more fragile form.

The current design says:

```text
outerIterator is only a lazy source of chunks
iterables caches every chunk once discovered
inner iterators handle marks inside chunks
indexes tell us where prev/next/mark are in the cached chunk list
```

The overhead exists because the concat iterator needs to support marks inside inner iterators, while still being able to continue through later chunks after a reset. Outer rewind only knows about chunk boundaries; it does not naturally solve the "resume from value 2 inside chunk 0, then continue to chunk 1" problem.

## Q7. In short, what is the concern for not using the `outerIterator`'s rewind method?

In short: the mark may be inside an inner iterator, not just at an outer-iterator boundary.

`outerIterator.reset()` can only help you return to some outer chunk, like:

```text
chunk 0: [1, 2, 3]
```

But the concat iterator may need to reset to:

```text
2
```

inside that chunk.

So the class keeps `markItemIterator`, because the inner iterator knows exactly where `2` was marked.

Second concern: after resetting into an old inner iterator and replaying the rest of that chunk, the concat iterator must continue to later chunks. Since `outerIterator` may have already moved past those chunks, the class caches them in `iterables`.

Core concern:

```text
outerIterator reset works at chunk level;
ConcatBacktrackingIterator reset must work at flattened item level.
```

That requires inner iterator state plus cached chunks.

## Q8. I did not say remove `markItemIterator`. Why not use `outerIterator.reset()` and `markItemIterator.reset()`? Also, why would `outerIterator` move past later chunks? It only moves if we call `next()`.

`outerIterator` will not move by itself. It moves only because `ConcatBacktrackingIterator` calls `outerIterator.next()` while scanning later chunks.

The real issue is that using `outerIterator.reset()` plus `markItemIterator.reset()` can work only if you also carefully mark the outer iterator at the right boundary. That boundary is not the marked item. It is usually the chunk after the marked chunk.

Example:

```text
chunks:
A = [1, 2, 3]
B = [4, 5]
C = [6]
```

Suppose:

```text
next() -> 1
next() -> 2
markPrev()  // mark 2 inside A
next() -> 3
next() -> 4
next() -> 5
reset()
```

After reset, you want:

```text
2, 3, 4, 5, 6
```

If you use `markItemIterator.reset()`, you can replay inside `A`:

```text
2, 3
```

But after `A` is exhausted, you need to continue with `B`.

So `outerIterator` must reset to the position where its next value is `B`, not `A`.

That means when you mark inside chunk `A`, you would need to also do something like:

```java
outerIterator.markNext(); // mark B, the chunk after A
```

Then on reset:

```java
markItemIterator.reset(); // back to 2 inside A
outerIterator.reset();    // back to B for after A finishes
```

This alternative design is possible.

But it has its own tricky bookkeeping:

```text
If there is no next outer chunk yet, outerIterator.markNext() may do nothing.
If there are empty chunks after A, you need to decide whether to mark the immediate next chunk or the next non-empty chunk.
After reset, the concat iterator must know it is currently replaying the old marked inner iterator, and only after that should it resume from outerIterator.
If marks are updated multiple times across different chunks, you must keep the outer mark synchronized with the inner mark.
```

So the current implementation avoids relying on outer backtracking. It treats `outerIterator` as a one-way lazy source and caches chunks in `iterables`. That makes reset local:

```java
nextItemIterator = markItemIterator;
nextIndex = markIndex;
```

Then `moveNextToNonEmpty()` uses `iterables` to continue to the later chunks.

## Q9. What if in `markPrev()` I call `markPrev()` of both `markItemIterator` and `outerIterator`, and in `reset()` call `reset()` of both? Since `markItemIterator` is just a reference of the inner iterator in the outer iterator, when it is exhausted, we call `next()` on the outer iterator and get the new inner iterator.

The key correction is:

`markItemIterator` is not an iterator "inside" `outerIterator`.

`outerIterator` yields `BacktrackingIterable<T>` objects:

```java
BacktrackingIterator<BacktrackingIterable<T>> outerIterator;
```

Then this class calls:

```java
iterable.iterator()
```

to create an inner `BacktrackingIterator<T>`.

So the outer iterator knows about chunks, not the current inner iterator state.

Example:

```text
outer chunks:
A = [1, 2, 3]
B = [4, 5]
```

Suppose we are iterating inside `A`:

```text
outerIterator.next() -> A
inner iterator over A returns 1
inner iterator over A returns 2
markPrev() // mark 2
```

If your `markPrev()` does:

```java
markItemIterator.markPrev(); // marks 2 inside A
outerIterator.markPrev();    // marks A
```

Then later:

```text
next() -> 3
next() -> 4
reset()
```

Now:

```java
markItemIterator.reset(); // goes back to 2 inside A
outerIterator.reset();    // goes back so next outer item is A
```

Replay starts correctly inside old `A`:

```text
next() -> 2
next() -> 3
```

But now old `A` is exhausted. What should come next?

Correct:

```text
4, 5
```

But `outerIterator.reset()` put the outer iterator back at `A`, so calling:

```java
outerIterator.next()
```

returns `A` again, not `B`.

That gives you:

```text
2, 3, 1, 2, 3, 4, 5
```

or forces you to add special logic to skip `A` after replaying the marked inner iterator.

So calling `outerIterator.markPrev()` is the wrong outer mark. It marks the current chunk, but after replaying the old inner iterator, you need the next chunk.

You might instead try:

```java
outerIterator.markNext(); // mark B
```

while marking inside `A`. Then after reset:

```text
markItemIterator.reset() -> replay 2, 3
outerIterator.reset()    -> next outer item is B
```

That idea is closer. But now you need extra state:

```text
am I replaying old marked inner iterator?
after it exhausts, should I resume from outerIterator?
what if there is no next outer chunk?
what if the next chunks are empty?
what if mark changes multiple times?
```

So this approach is possible, but not just "call reset on both." If you call `outerIterator.markPrev()`, you duplicate the marked chunk. If you call `outerIterator.markNext()`, you still need careful coordination.

The current implementation avoids that by caching chunks in `iterables` and using indexes. It does not need to manipulate the outer iterator's mark at all. It treats the outer iterator as a one-way chunk supplier, and handles flattened reset itself.

## Q10. Why not use `BacktrackingIterator<BacktrackingIterator<T>>` directly?

You could imagine:

```java
BacktrackingIterator<BacktrackingIterator<T>>
```

meaning:

```text
outer iterator yields inner iterators directly
```

But this class uses:

```java
BacktrackingIterator<BacktrackingIterable<T>>
```

meaning:

```text
outer iterator yields things that can create inner iterators
```

The difference matters because an `Iterable` is reusable, while an `Iterator` is usually stateful and consumable.

Example:

```java
BacktrackingIterable<Integer> xs = ...
BacktrackingIterator<Integer> a = xs.iterator();
BacktrackingIterator<Integer> b = xs.iterator();
```

`a` and `b` can be separate iterator objects over the same logical chunk.

But if the outer iterator gives you this directly:

```java
BacktrackingIterator<Integer> inner = outer.next();
```

then that one `inner` object is the chunk. Once it has been advanced, it carries state.

The current `ConcatBacktrackingIterator` often needs to recreate an iterator for a cached chunk:

```java
this.nextItemIterator = iterables.get(nextIndex).iterator();
```

That line depends on `BacktrackingIterable<T>`, not `BacktrackingIterator<T>`.

This is useful when moving from one cached chunk to another after reset. The class stores the chunk source:

```java
List<BacktrackingIterable<T>> iterables;
```

not just one already-mutated iterator.

Concrete example from `Table.ridIterator()`:

```java
return new ConcatBacktrackingIterator<>(new PageIterator(iter, false));
```

`PageIterator` yields `BacktrackingIterable<RecordId>` objects. Each one represents a page. When its `iterator()` is called, it creates a fresh `RIDPageIterator` over that page.

That is a natural model:

```text
page -> iterable of record IDs
```

rather than:

```text
page -> one single record ID iterator object forever
```

Short answer:

```text
BacktrackingIterable<T> = reusable chunk
BacktrackingIterator<T> = one traversal state over a chunk
```

`ConcatBacktrackingIterator` wants to cache reusable chunks, then create traversal state from them when needed. That is why it uses `BacktrackingIterator<BacktrackingIterable<T>>`.

## Q11. So we have `markIndex` and `prevIndex`. Why do we need `markItemIterator` and `prevItemIterator`? We can get from `iterables` through index.

Because `iterables.get(index).iterator()` gives you a new inner iterator, not the same inner iterator with the current position/mark state.

The index tells you which chunk:

```text
chunk 0: [1, 2, 3]
chunk 1: [4, 5]
```

But it does not tell you where inside that chunk the iterator currently is.

Example:

```text
next() -> 1
next() -> 2
```

At this point:

```java
prevIndex = 0
prevItemIterator = iterator over chunk 0, positioned after 2
```

Now call:

```java
markPrev()
```

Correct behavior: mark value `2`.

If the code only had `prevIndex`, it might do:

```java
BacktrackingIterator<T> iter = iterables.get(prevIndex).iterator();
iter.markPrev();
```

But that is a fresh iterator over `[1, 2, 3]`. It has not returned anything yet, so `markPrev()` does nothing. It does not know that the concat iterator previously returned `2`.

So `prevItemIterator` is needed because it is the exact inner iterator object that returned the previous item.

Same reason for `markItemIterator`.

When you do:

```java
markPrev(); // mark 2
```

the mark is stored inside the inner iterator object:

```java
markItemIterator.markPrev();
```

Later:

```java
reset();
```

needs to call:

```java
markItemIterator.reset();
```

If you only had `markIndex`, you could find the chunk, but not the marked position inside the chunk. A new iterator from that chunk would start at the beginning, not at `2`.

So the split is:

```text
index fields: which chunk are we in?
iterator fields: what is the state inside that chunk?
```

You need both because `ConcatBacktrackingIterator` tracks position at two levels:

```text
outer level: chunk number
inner level: item position within that chunk
```

## Q12. I know `iterables` are useful, and the iterators are useful, but what is the role of these indexes?

The indexes connect the two worlds:

```text
iterators = exact state inside a chunk
indexes   = which chunk that iterator belongs to in iterables
```

The iterator objects alone know their inner position, but they do not know their own position inside `iterables`.

Example:

```text
iterables:
0: [1, 2]
1: [3, 4]
2: [5, 6]
```

Suppose:

```text
next() -> 1
next() -> 2
markPrev()  // mark 2 in chunk 0
next() -> 3
next() -> 4
reset()
```

After reset, the code does:

```java
nextItemIterator = markItemIterator;
nextItemIterator.reset();
nextIndex = markIndex;
```

Why is `nextIndex = markIndex` needed?

Because after reset, `nextItemIterator` is back in chunk `0`. When chunk `0` is exhausted, `moveNextToNonEmpty()` needs to know the next chunk is chunk `1`.

It does that using:

```java
if (nextIndex + 1 < iterables.size()) {
    nextIndex++;
    nextItemIterator = iterables.get(nextIndex).iterator();
}
```

If you had no `nextIndex`, the code would not know where `nextItemIterator` sits inside `iterables`, so it would not know which cached chunk comes after it.

The three indexes have specific roles:

```java
nextIndex
```

Tracks which chunk `nextItemIterator` belongs to. Needed to move to the following cached chunk.

```java
prevIndex
```

Tracks which chunk `prevItemIterator` belongs to. Needed when `markPrev()` turns the previous iterator into the marked iterator:

```java
markItemIterator = prevItemIterator;
markIndex = prevIndex;
```

Without `prevIndex`, you could mark the previous inner iterator, but later you would not know which chunk the mark belongs to.

```java
markIndex
```

Tracks which chunk `markItemIterator` belongs to. Needed during `reset()`:

```java
nextItemIterator = markItemIterator;
nextItemIterator.reset();
nextIndex = markIndex;
```

Without `markIndex`, reset could restore the inner iterator state, but `moveNextToNonEmpty()` would later not know which chunk should come next.

Short answer:

```text
The iterator fields remember inner position.
The index fields remember outer position.
```

You need the indexes so that after a reset, the concat iterator can continue from the correct next chunk in `iterables`.
