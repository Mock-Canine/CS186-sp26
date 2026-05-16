package edu.berkeley.cs186.database.common.iterator;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Iterator that concatenates a group of backtracking iterables together.
 * For example, if you had backtracking iterators containing the following:
 * - [1,2,3]
 * - []
 * - [4,5,6]
 * - [7,8]
 *
 * Concatenating them with this class would produce a backtracking iterator
 * over the values [1,2,3,4,5,6,7,8].
 *
 * Design note: a mark may land mid-chunk (e.g. on value 2 inside [1,2,3]),
 * which is sub-chunk granularity. outerIterator.reset() can only rewind to
 * chunk boundaries, so this class deliberately does NOT use outerIterator's
 * backtracking. Instead:
 *
 *   - outerIterator is treated as a forward-only, lazy source of chunks
 *     (only hasNext()/next() are ever called on it).
 *   - Every chunk pulled from outerIterator is cached in `iterables`, so a
 *     reset() can replay later chunks without rewinding outerIterator.
 *   - The mark lives inside an inner iterator instance (set via its own
 *     markPrev/markNext); we just hold a reference to that instance in
 *     markItemIterator and remember which cached slot it came from.
 *
 * Example layout after consuming 1,2,3,4,5,6 with mark set on 2:
 *
 *       outerIterator (forward-only; already advanced past chunks 0..2)
 *            |
 *            v  (more chunks pulled lazily by moveNextToNonEmpty)
 *      iterables (cached):
 *      +---------+   +---------+   +---------+   . . . . . .
 *      | chunk 0 |   | chunk 1 |   | chunk 2 |   . chunk 3 .   not pulled
 *      | [1,2,3] |   | [4,5,6] |   |  [7,8]  |   .   ???   .   yet
 *      +---------+   +---------+   +---------+   . . . . . .
 *           ^             ^             ^
 *           |             |             |
 *         mark          prev          next
 *        markIndex=0   prevIndex=1   nextIndex=2
 *        (mark stored  (last next()  (next() will
 *         INSIDE the    returned 6   return 7)
 *         chunk 0       from chunk 1)
 *         iterator,
 *         at value 2)
 *
 * Each (iterator, index) pair answers two different questions:
 *
 *   iterator reference -> where inside a chunk are we?
 *   index field        -> which slot in `iterables` does that iterator live in?
 *
 * Both are needed: the iterator's inner cursor/mark cannot be reconstructed
 * from the index alone (a fresh `iterables.get(i).iterator()` would have an
 * unset mark), and the index cannot be recovered from the iterator alone
 * (the inner iterator does not know its position in our cache).
 */
public class ConcatBacktrackingIterator<T> implements BacktrackingIterator<T> {
    // Iterator of iterables that we're concatenating
    private BacktrackingIterator<BacktrackingIterable<T>> outerIterator;
    // List of iterables we're concatenating
    private List<BacktrackingIterable<T>> iterables;
    // The iterator that we yielded the previous item from
    private BacktrackingIterator<T> prevItemIterator;
    // The iterator we'll be yielding the next item from
    private BacktrackingIterator<T> nextItemIterator;
    // The iterator with the item that's currently marked
    private BacktrackingIterator<T> markItemIterator;
    // Indices of the above three iterator's source iterables
    private int prevIndex = -1;
    private int nextIndex = -1;
    private int markIndex = -1;

    /**
     * @param outerIterator An iterator over iterable objects which we want to concatenate.
     *                      Any values this iterator yields will be drawn from iterators created
     *                      from outerIterator's contents.
     */
    public ConcatBacktrackingIterator(BacktrackingIterator<BacktrackingIterable<T>> outerIterator) {
        this.iterables = new ArrayList<>();
        this.outerIterator = outerIterator;
        this.prevItemIterator = null;
        // Null-object pattern: lets hasNext() call nextItemIterator.hasNext()
        // before any real inner iterator has been loaded, without a null check.
        this.nextItemIterator = new EmptyBacktrackingIterator<>();
        this.markItemIterator = null;
    }

    /**
     * Sets nextItemIterator to the next non-empty iterator in our collection, or the last one if all remaining
     * iterators are empty. Lazily adds in the iterables from outerIterator as needed to the list of iterables.
     *
     * Only ever advances nextIndex forward. After a reset() has moved nextIndex
     * backward, this method walks forward again through already-cached chunks
     * before consulting outerIterator — which is why every chunk pulled from
     * outerIterator must be kept in `iterables`.
     */
    private void moveNextToNonEmpty() {
        while (!this.nextItemIterator.hasNext()) {
            if (nextIndex + 1 < iterables.size()) {
                nextIndex++;
                // Fresh iterator over a cached chunk. If this chunk previously
                // held a mark, its old iterator instance (and that mark) is
                // discarded here — safe, because markItemIterator still holds
                // the only reference that matters until reset() runs.
                this.nextItemIterator = iterables.get(nextIndex).iterator();
            } else {
                assert(nextIndex + 1 == iterables.size());
                if (!outerIterator.hasNext()) break;
                iterables.add(outerIterator.next());
            }
        }
    }

    @Override
    public boolean hasNext() {
        if (!this.nextItemIterator.hasNext()) this.moveNextToNonEmpty();
        return this.nextItemIterator.hasNext();
    }

    @Override
    public T next() {
        if (!hasNext()) throw new NoSuchElementException();
        T item = this.nextItemIterator.next();
        this.prevItemIterator = this.nextItemIterator;
        prevIndex = nextIndex;
        return item;
    }

    @Override
    public void markPrev() {
        if (prevIndex == -1) {
            // We haven't yielded an item yet, or since the most recent reset
            return;
        }
        this.markItemIterator = this.prevItemIterator;
        this.markItemIterator.markPrev();
        markIndex = prevIndex;
    }

    @Override
    public void markNext() {
        // hasNext() advances nextItemIterator past empty/exhausted chunks
        // before we mark, so the mark lands on a real upcoming value.
        // e.g. with chunks [1,2], [], [3,4] after consuming 1 and 2,
        // nextItemIterator is still on the exhausted chunk 0; hasNext()
        // moves it to chunk 2, so markNext() correctly marks 3.
        if (!hasNext()) return;
        this.markItemIterator = this.nextItemIterator;
        this.markItemIterator.markNext();
        markIndex = nextIndex;
    }

    @Override
    public void reset() {
        if (markIndex == -1) {
            // Nothing was marked
            return;
        }
        // We no longer have a prev
        prevItemIterator = null;
        prevIndex = -1;

        // Set our next to wherever was marked
        this.nextItemIterator = this.markItemIterator;
        this.nextItemIterator.reset();
        nextIndex = markIndex;
    }
}