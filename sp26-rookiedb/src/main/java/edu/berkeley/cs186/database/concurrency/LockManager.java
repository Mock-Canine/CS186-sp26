package edu.berkeley.cs186.database.concurrency;

import edu.berkeley.cs186.database.TransactionContext;

import java.util.*;

/**
 * LockManager maintains the bookkeeping for what transactions have what locks
 * on what resources and handles queuing logic. The lock manager should generally
 * NOT be used directly: instead, code should call methods of LockContext to
 * acquire/release/promote/escalate locks.
 *
 * The LockManager is primarily concerned with the mappings between
 * transactions, resources, and locks, and does not concern itself with multiple
 * levels of granularity. Multigranularity is handled by LockContext instead.
 *
 * Each resource the lock manager manages has its own queue of LockRequest
 * objects representing a request to acquire (or promote/acquire-and-release) a
 * lock that could not be satisfied at the time. This queue should be processed
 * every time a lock on that resource gets released, starting from the first
 * request, and going in order until a request cannot be satisfied. Requests
 * taken off the queue should be treated as if that transaction had made the
 * request right after the resource was released in absence of a queue (i.e.
 * removing a request by T1 to acquire X(db) should be treated as if T1 had just
 * requested X(db) and there were no queue on db: T1 should be given the X lock
 * on db, and put in an unblocked state via Transaction#unblock).
 *
 * This does mean that in the case of:
 *    queue: S(A) X(A) S(A)
 * only the first request should be removed from the queue when the queue is
 * processed.
 */
public class LockManager {
    // transactionLocks is a mapping from transaction number to a list of lock
    // objects held by that transaction.
    private Map<Long, List<Lock>> transactionLocks = new HashMap<>();

    // resourceEntries is a mapping from resource names to a ResourceEntry
    // object, which contains a list of Locks on the object, as well as a
    // queue for requests on that resource.
    private Map<ResourceName, ResourceEntry> resourceEntries = new HashMap<>();

    // A ResourceEntry contains the list of locks on a resource, as well as
    // the queue for requests for locks on the resource.
    private class ResourceEntry {
        // List of currently granted locks on the resource.
        List<Lock> locks = new ArrayList<>();
        // Queue for yet-to-be-satisfied lock requests on this resource.
        Deque<LockRequest> waitingQueue = new ArrayDeque<>();

        // Below are a list of helper methods we suggest you implement.
        // You're free to modify their type signatures, delete, or ignore them.

        /**
         * Check if `lockType` is compatible with preexisting locks. Allows
         * conflicts for locks held by transaction with id `except`, which is
         * useful when a transaction tries to replace a lock it already has on
         * the resource.
         */
        public boolean checkCompatible(LockType lockType, long except) {
            for (Lock lk : locks) {
                if (lk.transactionNum != except && !LockType.compatible(lk.lockType, lockType)) return false;
            }
            return true;
        }

        /**
         * Gives the transaction the lock `lock`. Assumes that the lock is
         * compatible. Updates lock on resource if the transaction already has a
         * lock.
         */
        public void grantOrUpdateLock(Lock lock) {
            Long txnNum = lock.transactionNum;
            List<Lock> txnLocks = transactionLocks.get(txnNum);
            int idx = -1;
            for (int i = 0; i < locks.size(); i++) {
                if (locks.get(i).transactionNum.equals(txnNum)) idx = i;
            }
            // UpdateLock
            if (idx != -1) {
                locks.set(idx, lock);
                txnLocks.set(idx, lock);
            } else {
                locks.add(lock);
                transactionLocks.putIfAbsent(txnNum, new ArrayList<>());
                transactionLocks.get(txnNum).add(lock);
            }
        }

        /**
         * Releases the lock `lock` and processes the queue. Assumes that the
         * lock has been granted before.
         */
        public void releaseLock(Lock lock) {
            transactionLocks.get(lock.transactionNum).remove(lock);
            locks.remove(lock);
            processQueue();
        }

        /**
         * Adds `request` to the front of the queue if addFront is true, or to
         * the end otherwise.
         */
        public void addToQueue(LockRequest request, boolean addFront) {
            if (addFront) waitingQueue.addFirst(request);
            else waitingQueue.addLast(request);
        }

        /**
         * Grant locks to requests from front to back of the queue, stopping
         * when the next lock cannot be granted. Once a request is completely
         * granted, the transaction that made the request can be unblocked.
         */
        private void processQueue() {
            while (!waitingQueue.isEmpty()) {
                LockRequest lr = waitingQueue.peek();
                if (!checkCompatible(lr.lock.lockType, lr.transaction.getTransNum())) break;
                waitingQueue.poll();
                grantOrUpdateLock(lr.lock);
                // Release
                // TODO: different from others code
                for (Lock lk : lr.releasedLocks) {
                    ResourceEntry entry = getResourceEntry(lk.name);
                    entry.releaseLock(lk);
                }
                lr.transaction.unblock();
            }
        }

        /**
         * Gets the type of lock `transaction` has on this resource.
         */
        public LockType getTransactionLockType(long transaction) {
            for (Lock lk : locks) {
                if (lk.transactionNum == transaction) return lk.lockType;
            }
            return LockType.NL;
        }

        @Override
        public String toString() {
            return "Active Locks: " + Arrays.toString(this.locks.toArray()) +
                    ", Queue: " + Arrays.toString(this.waitingQueue.toArray());
        }
    }

    // You should not modify or use this directly.
    private Map<String, LockContext> contexts = new HashMap<>();

    /**
     * Helper method to fetch the resourceEntry corresponding to `name`.
     * Inserts a new (empty) resourceEntry into the map if no entry exists yet.
     */
    private ResourceEntry getResourceEntry(ResourceName name) {
        resourceEntries.putIfAbsent(name, new ResourceEntry());
        return resourceEntries.get(name);
    }

    /**
     * Acquire a `lockType` lock on `name`, for transaction `transaction`, and
     * releases all locks on `releaseNames` held by the transaction after
     * acquiring the lock in one atomic action.
     *
     * Error checking must be done before any locks are acquired or released. If
     * the new lock is not compatible with another transaction's lock on the
     * resource, the transaction is blocked and the request is placed at the
     * FRONT of the resource's queue.
     *
     * Locks on `releaseNames` should be released only after the requested lock
     * has been acquired. The corresponding queues should be processed.
     *
     * An acquire-and-release that releases an old lock on `name` should NOT
     * change the acquisition time of the lock on `name`, i.e. if a transaction
     * acquired locks in the order: S(A), X(B), acquire X(A) and release S(A),
     * the lock on A is considered to have been acquired before the lock on B.
     *
     * @throws DuplicateLockRequestException if a lock on `name` is already held
     * by `transaction` and isn't being released
     * @throws NoLockHeldException if `transaction` doesn't hold a lock on one
     * or more of the names in `releaseNames`
     */
    // As the name suggests, its job is simple: acquire a lock and release some atomically
    // Cover the job of promote(but without promotion check)
    // We do not need to think of all the usage situations at LockContext level(Promote, Acquire, Escalate, etc) now.
    // We just know: Ok, we need to get a lock(so no previous lock existed or the previous lock is going to be released)
    // and we will release the txn's locks on other resources at the same time(so there should be a lock)
    public void acquireAndRelease(TransactionContext transaction, ResourceName name,
                                  LockType lockType, List<ResourceName> releaseNames)
            throws DuplicateLockRequestException, NoLockHeldException {
        boolean shouldBlock;
        synchronized (this) {
            ResourceEntry entry = getResourceEntry(name);
            LockType heldLockType = entry.getTransactionLockType(transaction.getTransNum());
            // No lock -> can acquire; promote(A -> B), A should be released later
            if (heldLockType != LockType.NL && !releaseNames.contains(name)) {
                throw new DuplicateLockRequestException("txn already has the same lock on this resource.");
            }
            // Build released locks while checking
            List<Lock> released = new ArrayList<>();
            for (ResourceName resourceName : releaseNames) {
                ResourceEntry rn = getResourceEntry(resourceName);
                LockType releasedLockType = rn.getTransactionLockType(transaction.getTransNum());
                if (releasedLockType == LockType.NL) {
                    throw new NoLockHeldException("No lock on the released resources.");
                }
                released.add(new Lock(resourceName, releasedLockType, transaction.getTransNum()));
            }
            shouldBlock = isShouldBlock(transaction, name, lockType, entry, released, true);
        }
        if (shouldBlock) {
            transaction.block();
        }
    }

    /**
     * Acquire a `lockType` lock on `name`, for transaction `transaction`.
     *
     * Error checking must be done before the lock is acquired. If the new lock
     * is not compatible with another transaction's lock on the resource, or if there are
     * other transaction in queue for the resource, the transaction is
     * blocked and the request is placed at the **back** of NAME's queue.
     *
     * @throws DuplicateLockRequestException if a lock on `name` is held by
     * `transaction`
     */
    // Very simple, check and get a lock
    public void acquire(TransactionContext transaction, ResourceName name,
                        LockType lockType) throws DuplicateLockRequestException {
        boolean shouldBlock;
        synchronized (this) {
            ResourceEntry entry = getResourceEntry(name);
            // No lock -> can acquire
            if (entry.getTransactionLockType(transaction.getTransNum()) != LockType.NL)
                throw new DuplicateLockRequestException("A lock has been held by the current txn.");
            shouldBlock = isShouldBlock(transaction, name, lockType, entry, Collections.emptyList(), false);
        }
        if (shouldBlock) {
            transaction.block();
        }
    }

    /**
     * Release `transaction`'s lock on `name`. Error checking must be done
     * before the lock is released.
     *
     * The resource name's queue should be processed after this call. If any
     * requests in the queue have locks to be released, those should be
     * released, and the corresponding queues also processed.
     *
     * @throws NoLockHeldException if no lock on `name` is held by `transaction`
     */
    // Very simple, check and release a lock
    public void release(TransactionContext transaction, ResourceName name)
            throws NoLockHeldException {
        synchronized (this) {
            ResourceEntry entry = getResourceEntry(name);
            LockType lt = entry.getTransactionLockType(transaction.getTransNum());
            if (lt == LockType.NL) {
                throw new NoLockHeldException("No lock on desired resource is held.");
            }
            entry.releaseLock(new Lock(name, lt, transaction.getTransNum()));
        }
    }

    /**
     * Promote a transaction's lock on `name` to `newLockType` (i.e. change
     * the transaction's lock on `name` from the current lock type to
     * `newLockType`, if its a valid substitution).
     *
     * Error checking must be done before any locks are changed. If the new lock
     * is not compatible with another transaction's lock on the resource, the
     * transaction is blocked and the request is placed at the FRONT of the
     * resource's queue.
     *
     * A lock promotion should NOT change the acquisition time of the lock, i.e.
     * if a transaction acquired locks in the order: S(A), X(B), promote X(A),
     * the lock on A is considered to have been acquired before the lock on B.
     *
     * @throws DuplicateLockRequestException if `transaction` already has a
     * `newLockType` lock on `name`
     * @throws NoLockHeldException if `transaction` has no lock on `name`
     * @throws InvalidLockException if the requested lock type is not a
     * promotion. A promotion from lock type A to lock type B is valid if and
     * only if B is substitutable for A, and B is not equal to A.
     */
    // The spec says when it comes to promoting to SIX, we turn to acquireAndRelease(), but that is not what we need to
    // consider at this level.
    // Its job is basically acquireAndRelease()'s single resource version but with promotion check.
    public void promote(TransactionContext transaction, ResourceName name,
                        LockType newLockType)
            throws DuplicateLockRequestException, NoLockHeldException, InvalidLockException {
        boolean shouldBlock;
        synchronized (this) {
            ResourceEntry entry = getResourceEntry(name);
            LockType lt = entry.getTransactionLockType(transaction.getTransNum());
            if (lt == newLockType) {
                throw new DuplicateLockRequestException("txn already has the same lock on this resource.");
            } else if (lt == LockType.NL) {
                throw new NoLockHeldException("No lock on desired resource is held.");
            } else if (!LockType.substitutable(newLockType, lt)) {
                throw new InvalidLockException("It is not a promotion.");
            }
            shouldBlock = isShouldBlock(transaction, name, newLockType, entry, Collections.emptyList(), true);
        }
        if (shouldBlock) {
            transaction.block();
        }
    }

    /**
     * Grant the lock if possible, otherwise add the request in the queue, used when acquire/promote a lock,
     * Provide releasedLocks if needed, Collections.emptyList otherwise
     * @param priority if priority is true, skip existing queue if compatible or enqueue at the front.
     */
    private boolean isShouldBlock(TransactionContext txn, ResourceName name, LockType grantLock,
                                  ResourceEntry entry, List<Lock> releasedLocks, boolean priority) {
        assert(releasedLocks != null);
        Lock lk = new Lock(name, grantLock, txn.getTransNum());
        if ((priority || entry.waitingQueue.isEmpty()) && entry.checkCompatible(grantLock, txn.getTransNum())) {
            // Directly grant and release
            entry.grantOrUpdateLock(lk);
            for (Lock releasedLock : releasedLocks) {
                ResourceName releasedResource = releasedLock.name;
                ResourceEntry rn = getResourceEntry(releasedResource);
                rn.releaseLock(releasedLock);
            }
            return false;
        } else {
            // Wrap to a request(grant and release)
            if (releasedLocks.isEmpty()) entry.addToQueue(new LockRequest(txn, lk), priority);
            else entry.addToQueue(new LockRequest(txn, lk, releasedLocks), priority);
            txn.prepareBlock();
            return true;
        }
    }

    /**
     * Return the type of lock `transaction` has on `name` or NL if no lock is
     * held.
     */
    public synchronized LockType getLockType(TransactionContext transaction, ResourceName name) {
        ResourceEntry resourceEntry = getResourceEntry(name);
        return resourceEntry.getTransactionLockType(transaction.getTransNum());
    }

    /**
     * Returns the list of locks held on `name`, in order of acquisition.
     */
    public synchronized List<Lock> getLocks(ResourceName name) {
        return new ArrayList<>(resourceEntries.getOrDefault(name, new ResourceEntry()).locks);
    }

    /**
     * Returns the list of locks held by `transaction`, in order of acquisition.
     */
    public synchronized List<Lock> getLocks(TransactionContext transaction) {
        return new ArrayList<>(transactionLocks.getOrDefault(transaction.getTransNum(),
                Collections.emptyList()));
    }

    /**
     * Creates a lock context. See comments at the top of this file and the top
     * of LockContext.java for more information.
     */
    public synchronized LockContext context(String name) {
        if (!contexts.containsKey(name)) {
            contexts.put(name, new LockContext(this, null, name));
        }
        return contexts.get(name);
    }

    /**
     * Create a lock context for the database. See comments at the top of this
     * file and the top of LockContext.java for more information.
     */
    public synchronized LockContext databaseContext() {
        return context("database");
    }
}
