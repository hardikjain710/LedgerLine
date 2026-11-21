package com.hardik.billing.ledger;

public final class LedgerTotals {

    private long debits;
    private long credits;

    public void add(LedgerDirection direction, long amount) {
        switch (direction) {
            case DEBIT -> debits = Math.addExact(debits, amount);
            case CREDIT -> credits = Math.addExact(credits, amount);
        }
    }

    public boolean isBalanced() {
        return debits == credits;
    }

    public long balance(LedgerAccount.Type type) {
        return switch (type) {
            case ASSET, CONTRA_REVENUE -> Math.subtractExact(debits, credits);
            case REVENUE -> Math.subtractExact(credits, debits);
        };
    }

    public long debits() {
        return debits;
    }

    public long credits() {
        return credits;
    }
}
