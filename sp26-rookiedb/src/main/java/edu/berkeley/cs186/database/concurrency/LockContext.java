package edu.berkeley.cs186.database.concurrency;

import edu.berkeley.cs186.database.TransactionContext;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LockContext wraps around LockManager to provide the hierarchical structure
 * of multigranularity locking. Calls to acquire/release/etc. locks should
 * be mostly done through a LockContext, which provides access to locking
 * methods at a certain point in the hierarchy (database, table X, etc.)
 */
public class LockContext {
    // You should not remove any of these fields. You may add additional
    // fields/methods as you see fit.

    // The underlying lock manager.
    protected final LockManager lockman;

    // The parent LockContext object, or null if this LockContext is at the top of the hierarchy.
    protected final LockContext parent;

    // The name of the resource this LockContext represents.
    protected ResourceName name;

    // Whether this LockContext is readonly. If a LockContext is readonly, acquire/release/promote/escalate should
    // throw an UnsupportedOperationException.
    protected boolean readonly;

    // A mapping between transaction numbers, and the number of locks on children of this LockContext
    // that the transaction holds.
    protected final Map<Long, Integer> numChildLocks;

    // You should not modify or use this directly.
    protected final Map<String, LockContext> children;

    // Whether or not any new child LockContexts should be marked readonly.
    protected boolean childLocksDisabled;

    public LockContext(LockManager lockman, LockContext parent, String name) {
        this(lockman, parent, name, false);
    }

    protected LockContext(LockManager lockman, LockContext parent, String name,
                          boolean readonly) {
        this.lockman = lockman;
        this.parent = parent;
        if (parent == null) {
            this.name = new ResourceName(name);
        } else {
            this.name = new ResourceName(parent.getResourceName(), name);
        }
        this.readonly = readonly;
        this.numChildLocks = new ConcurrentHashMap<>();
        this.children = new ConcurrentHashMap<>();
        this.childLocksDisabled = readonly;
    }

    /**
     * Gets a lock context corresponding to `name` from a lock manager.
     */
    public static LockContext fromResourceName(LockManager lockman, ResourceName name) {
        Iterator<String> names = name.getNames().iterator();
        LockContext ctx;
        String n1 = names.next();
        ctx = lockman.context(n1);
        while (names.hasNext()) {
            String n = names.next();
            ctx = ctx.childContext(n);
        }
        return ctx;
    }

    /**
     * Get the name of the resource that this lock context pertains to.
     */
    public ResourceName getResourceName() {
        return name;
    }

    /**
     * Acquire a `lockType` lock, for transaction `transaction`.
     *
     * Note: you must make any necessary updates to numChildLocks, or else calls
     * to LockContext#getNumChildren will not work properly.
     *
     * @throws InvalidLockException if the request is invalid
     * @throws DuplicateLockRequestException if a lock is already held by the
     * transaction.
     * @throws UnsupportedOperationException if context is readonly
     */
    // About SIX:
    // 1. If any existing ancestor is SIX, no S,IS or SIX descendent locks can be required --> avoid unnecessary locks;
    // 2. If we promote an existing lock to SIX, we need to release all the descendents of S/IS, but not SIX
    //    --> SIX descendent can be the parent of X, we can just replace SIX to IX, locks number can not be reduced,
    //    but IS descendent can only be the parent of IS, S or NL and read access has been ensured by SIX, so after
    //    promoting, locks number can be reduced here.
    // So in a word, if any ancestor is SIX, we can not acquire or promote to SIX, but if we can, we will only release
    // all the S/IS descendants.
    public void acquire(TransactionContext transaction, LockType lockType)
            throws InvalidLockException, DuplicateLockRequestException {
        if (readonly) {
            throw new UnsupportedOperationException("This context is read only.");
        }
        LockType heldLockType = this.getExplicitLockType(transaction);
        // Duplicate check exists both in LockContext & LockManager, but both are necessary.
        if (heldLockType != LockType.NL) {
            throw new DuplicateLockRequestException("A lock has been held by this txn.");
        }
        if (lockType == LockType.NL) {
            throw new InvalidLockException("Can not acquire a NL lock.");
        }
        boolean sixCheck = hasSIXAncestor(transaction) && (lockType == LockType.S || lockType == LockType.IS ||
                                                           lockType == LockType.SIX);
        if (sixCheck) {
            throw new InvalidLockException("With a SIX ancestor, this acquisition is redundant.");
        }
        // Check whether parent's permission allows this acquisition
        if (parent != null && !LockType.canBeParentLock(parent.getEffectiveLockType(transaction), lockType)) {
            throw new InvalidLockException("Existing parent lock does not allow this lock type.");
        }

        updateAncestorNumChildren(transaction, 1);
        lockman.acquire(transaction, this.name, lockType);
    }

    /**
     * Iteratively updates the number of locks held on children a single transaction for all ancestors
     * @param num # locks decrease or increase for each ancestor
     */
    private void updateAncestorNumChildren(TransactionContext txn, int num) {
        LockContext ancestor = this.parent;
        while (ancestor != null) {
            int original = ancestor.numChildLocks.getOrDefault(txn.getTransNum(), 0);
            ancestor.numChildLocks.put(txn.getTransNum(), original + num);
            ancestor = ancestor.parent;
        }
    }

    /**
     * Helper method for escalate() to clear up the numChildren of all descendants
     */
    private void updateDescendantNumChildren(TransactionContext txn, List<ResourceName> descendants) {
        for (ResourceName name : descendants) {
            LockContext descendant = fromResourceName(lockman, name);
            descendant.numChildLocks.put(txn.getTransNum(), 0);
        }
    }

    /**
     * Release `transaction`'s lock on `name`.
     *
     * Note: you *must* make any necessary updates to numChildLocks, or
     * else calls to LockContext#getNumChildren will not work properly.
     *
     * @throws NoLockHeldException if no lock on `name` is held by `transaction`
     * @throws InvalidLockException if the lock cannot be released because
     * doing so would violate multigranularity locking constraints
     * @throws UnsupportedOperationException if context is readonly
     */
    public void release(TransactionContext transaction)
            throws NoLockHeldException, InvalidLockException {
        if (readonly) {
            throw new UnsupportedOperationException("This context is read only.");
        }
        LockType lt = lockman.getLockType(transaction, name);
        if (lt == LockType.NL) {
            throw new NoLockHeldException("No lock to release.");
        }
        if (this.getNumChildren(transaction) != 0) {
            throw new InvalidLockException("Release finer locks before releasing this.");
        }

        updateAncestorNumChildren(transaction, -1);
        lockman.release(transaction, name);
    }

    /**
     * Promote `transaction`'s lock to `newLockType`. For promotion to SIX from
     * IS/IX, all S and IS locks on descendants must be simultaneously
     * released. The helper function sisDescendants may be helpful here.
     *
     * Note: you *must* make any necessary updates to numChildLocks, or else
     * calls to LockContext#getNumChildren will not work properly.
     *
     * @throws DuplicateLockRequestException if `transaction` already has a
     * `newLockType` lock
     * @throws NoLockHeldException if `transaction` has no lock
     * @throws InvalidLockException if the requested lock type is not a
     * promotion or promoting would cause the lock manager to enter an invalid
     * state (e.g. IS(parent), X(child)). A promotion from lock type A to lock
     * type B is valid if B is substitutable for A and B is not equal to A, or
     * if B is SIX and A is IS/IX/S, and invalid otherwise. hasSIXAncestor may
     * be helpful here.
     * @throws UnsupportedOperationException if context is readonly
     */
    public void promote(TransactionContext transaction, LockType newLockType)
            throws DuplicateLockRequestException, NoLockHeldException, InvalidLockException {
        if (readonly) {
            throw new UnsupportedOperationException("This context is read only.");
        }
        LockType heldLockType = lockman.getLockType(transaction, name);
        if (heldLockType == LockType.NL) {
            throw new NoLockHeldException("No lock to promote.");
        }
        if (heldLockType == newLockType) {
            throw new DuplicateLockRequestException("The same lock has been held by this txn.");
        }
        if (!LockType.substitutable(newLockType, heldLockType)) {
            throw new InvalidLockException("It is not a promotion.");
        }
        // Check whether parent's permission allows this acquisition
        if (parent != null && !LockType.canBeParentLock(parent.getEffectiveLockType(transaction), newLockType)) {
            throw new InvalidLockException("Existing parent lock does not allow this lock type.");
        }
        if (newLockType == LockType.SIX) {
            if (hasSIXAncestor(transaction)) {
                throw new InvalidLockException("Require a SIX under a SIX ancestor is redundant.");
            }
            List<ResourceName> resourceNameList = sisDescendants(transaction);
            updateAncestorNumChildren(transaction, -1 * resourceNameList.size() + 1);
            lockman.acquireAndRelease(transaction, name, newLockType, resourceNameList);
        } else {
            lockman.promote(transaction, name, newLockType);
        }
    }

    /**
     * Escalate `transaction`'s lock from descendants of this context to this
     * level, using either an S or X lock. There should be no descendant locks
     * after this call, and every operation valid on descendants of this context
     * before this call must still be valid. You should only make *one* mutating
     * call to the lock manager, and should only request information about
     * TRANSACTION from the lock manager.
     *
     * For example, if a transaction has the following locks:
     *
     *                    IX(database)
     *                    /         \
     *               IX(table1)    S(table2)
     *                /      \
     *    S(table1 page3)  X(table1 page5)
     *
     * then after table1Context.escalate(transaction) is called, we should have:
     *
     *                    IX(database)
     *                    /         \
     *               X(table1)     S(table2)
     *
     * You should not make any mutating calls if the locks held by the
     * transaction do not change (such as when you call escalate multiple times
     * in a row).
     *
     * Note: you *must* make any necessary updates to numChildLocks of all
     * relevant contexts, or else calls to LockContext#getNumChildren will not
     * work properly.
     *
     * @throws NoLockHeldException if `transaction` has no lock at this level
     * @throws UnsupportedOperationException if context is readonly
     */
    public void escalate(TransactionContext transaction) throws NoLockHeldException {
        if (readonly) {
            throw new UnsupportedOperationException("This context is read only.");
        }
        LockType heldLockType = lockman.getLockType(transaction, name);
        if (heldLockType == LockType.NL) {
            throw new NoLockHeldException("No lock to escalate.");
        }
        LockType acquireLock;
        List<ResourceName> released = descendants(transaction);
        released.add(name);
        // Determine which lock to escalate
        switch (heldLockType) {
            case S:
            case X:
                // No duplicate calls to escalate
                if (getNumChildren(transaction) == 0) return;
                acquireLock = heldLockType;
                break;
            case IS: acquireLock = LockType.S; break;
            case IX:
            case SIX: acquireLock = LockType.X; break;
            default: throw new UnsupportedOperationException("bad lock type");
        }
        lockman.acquireAndRelease(transaction, name, acquireLock, released);
        // Modify numberChildren
        updateAncestorNumChildren(transaction, -1 * released.size() + 1);
        numChildLocks.put(transaction.getTransNum(), 0);
        updateDescendantNumChildren(transaction, released);
    }

    /**
     * Get the type of lock that `transaction` holds at this level, or NL if no
     * lock is held at this level.
     */
    public LockType getExplicitLockType(TransactionContext transaction) {
        if (transaction == null) return LockType.NL;
        return lockman.getLockType(transaction, name);
    }

    /**
     * Gets the type of lock that the transaction has at this level, either
     * implicitly (e.g. explicit S lock at higher level implies S lock at this
     * level) or explicitly. Returns NL if there is no explicit nor implicit
     * lock.
     */
    public LockType getEffectiveLockType(TransactionContext transaction) {
        if (transaction == null) return LockType.NL;
        LockType explicitLockType = getExplicitLockType(transaction);
        if (parent == null || explicitLockType == LockType.X) return explicitLockType;

        LockContext ancestor = this.parent;
        LockType implicitLockType = LockType.NL;
        while (ancestor != null) {
            LockType lk = ancestor.implicitLockToChildren(transaction);
            if (lk == LockType.X) return LockType.X;
            if (lk == LockType.S) implicitLockType = lk;
            ancestor = ancestor.parent;
        }
        // implicitLockType should be S or NL now, explicitLockType should be any lock except X.
        if (implicitLockType == LockType.NL) return explicitLockType;
        if (explicitLockType == LockType.IX || explicitLockType == LockType.SIX) return LockType.SIX;
        return LockType.S;
    }

    /**
     * Return the lockType that can implicitly passed to its children, S, X or NL.
     */
    private LockType implicitLockToChildren(TransactionContext txn) {
        LockType explicitLockType = getExplicitLockType(txn);
        if (explicitLockType == LockType.S || explicitLockType == LockType.SIX) return LockType.S;
        else if (explicitLockType == LockType.X) return LockType.X;
        return LockType.NL;
    }

    /**
     * Helper method to see if the transaction holds a SIX lock at an ancestor
     * of this context
     * @param transaction the transaction
     * @return true if holds a SIX at an ancestor, false if not
     */
    private boolean hasSIXAncestor(TransactionContext transaction) {
        LockContext ancestor = this.parent;
        while (ancestor != null) {
            if (lockman.getLockType(transaction, ancestor.name) == LockType.SIX) return true;
            ancestor = ancestor.parent;
        }
        return false;
    }

    /**
     * Helper method to get a list of resourceNames of all locks that are S or
     * IS and are descendants of current context for the given transaction.
     * @param transaction the given transaction
     * @return a list of ResourceNames of descendants which the transaction
     * holds an S or IS lock.
     */
    private List<ResourceName> sisDescendants(TransactionContext transaction) {
        List<ResourceName> res = new ArrayList<>();
        List<Lock> locks = lockman.getLocks(transaction);
        for (Lock lk : locks) {
            if (lk.name.isDescendantOf(this.name) && (lk.lockType == LockType.S || lk.lockType == LockType.IS)) {
                res.add(lk.name);
            }
        }
        return res;
    }

    /**
     * Return all ResourceNames of descendants that the transaction holds a lock
     */
    private List<ResourceName> descendants(TransactionContext txn) {
        List<ResourceName> res = new ArrayList<>();
        List<Lock> locks = lockman.getLocks(txn);
        for (Lock lk : locks) {
            if (lk.name.isDescendantOf(this.name)) res.add(lk.name);
        }
        return res;
    }

    /**
     * Disables locking descendants. This causes all new child contexts of this
     * context to be readonly. This is used for indices and temporary tables
     * (where we disallow finer-grain locks), the former due to complexity
     * locking B+ trees, and the latter due to the fact that temporary tables
     * are only accessible to one transaction, so finer-grain locks make no
     * sense.
     */
    public void disableChildLocks() {
        this.childLocksDisabled = true;
    }

    /**
     * Gets the parent context.
     */
    public LockContext parentContext() {
        return parent;
    }

    /**
     * Gets the context for the child with name `name` and readable name
     * `readable`
     */
    public synchronized LockContext childContext(String name) {
        LockContext temp = new LockContext(lockman, this, name,
                this.childLocksDisabled || this.readonly);
        LockContext child = this.children.putIfAbsent(name, temp);
        if (child == null) child = temp;
        return child;
    }

    /**
     * Gets the context for the child with name `name`.
     */
    public synchronized LockContext childContext(long name) {
        return childContext(Long.toString(name));
    }

    /**
     * Gets the number of locks held on children a single transaction.
     */
    public int getNumChildren(TransactionContext transaction) {
        return numChildLocks.getOrDefault(transaction.getTransNum(), 0);
    }

    @Override
    public String toString() {
        return "LockContext(" + name.toString() + ")";
    }
}

