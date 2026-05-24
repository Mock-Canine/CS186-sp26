package edu.berkeley.cs186.database.concurrency;

/**
 * Utility methods to track the relationships between different lock types.
 */
public enum LockType {
    S,   // shared
    X,   // exclusive
    IS,  // intention shared
    IX,  // intention exclusive
    SIX, // shared intention exclusive
    NL;  // no lock held

    /**
     * This method checks whether lock types A and B are compatible with
     * each other. If a transaction can hold lock type A on a resource
     * at the same time another transaction holds lock type B on the same
     * resource, the lock types are compatible.
     */
    public static boolean compatible(LockType a, LockType b) {
        if (a == null || b == null) {
            throw new NullPointerException("null lock type");
        }
        if (b == NL) return true;
        switch (a) {
            case S: return b == IS || b == S;
            case X: return false;
            case IS: return b != X;
            case IX: return b == IS || b == IX;
            case SIX: return b == IS;
            case NL: return true;
            default: throw new UnsupportedOperationException("bad lock type");
        }
    }

    /**
     * This method returns the lock on the parent resource
     * that should be requested for a lock of type A to be granted.
     */
    public static LockType parentLock(LockType a) {
        if (a == null) {
            throw new NullPointerException("null lock type");
        }
        switch (a) {
        case S: return IS;
        case X: return IX;
        case IS: return IS;
        case IX: return IX;
        case SIX: return IX;
        case NL: return NL;
        default: throw new UnsupportedOperationException("bad lock type");
        }
    }

    /**
     * This method returns if parentLockType has permissions to grant a childLockType
     * on a child.
     */
    // Matrix is also weird here(codex makes it), SIX has the ability that S, IS, SIX needs, but redundant.
    // But may be this func just think of permissions, not redundant.
    public static boolean canBeParentLock(LockType p, LockType c) {
        if (p == null || c == null) {
            throw new NullPointerException("null lock type");
        }
        if (p == X) return true;
        switch (c) {
            case IX:
            case SIX:
            case X: return p == IX || p == SIX;
            case NL: return true;
            case IS:
            case S: return p == IS || p == IX || p == SIX;
            default: throw new UnsupportedOperationException("bad lock type");
        }
    }

    /**
     * This method returns whether a lock can be used for a situation
     * requiring another lock (e.g. an S lock can be substituted with
     * an X lock, because an X lock allows the transaction to do everything
     * the S lock allowed it to do).
     */
    // I really doubt about the substitute matrix(codex makes it), why S can not substitute IS?
    public static boolean substitutable(LockType substitute, LockType required) {
        if (required == null || substitute == null) {
            throw new NullPointerException("null lock type");
        }
        if (substitute == required || substitute == X) return true;
        switch (required) {
            case IX:
            case S: return substitute == SIX;
            case X:
            case SIX: return false;
            case NL: return true;
            case IS: return substitute == IX || substitute == SIX;
            default: throw new UnsupportedOperationException("bad lock type");
        }
    }

    /**
     * @return True if this lock is IX, IS, or SIX. False otherwise.
     */
    public boolean isIntent() {
        return this == LockType.IX || this == LockType.IS || this == LockType.SIX;
    }

    @Override
    public String toString() {
        switch (this) {
        case S: return "S";
        case X: return "X";
        case IS: return "IS";
        case IX: return "IX";
        case SIX: return "SIX";
        case NL: return "NL";
        default: throw new UnsupportedOperationException("bad lock type");
        }
    }
}

