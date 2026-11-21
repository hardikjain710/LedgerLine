package com.hardik.billing.ledger.service;

import com.hardik.billing.ledger.LedgerAccount;
import com.hardik.billing.ledger.LedgerAccountRepository;
import com.hardik.billing.ledger.LedgerDirection;
import com.hardik.billing.ledger.LedgerEntryRepository;
import com.hardik.billing.ledger.LedgerKind;
import com.hardik.billing.ledger.LedgerTotals;
import com.hardik.billing.ledger.LedgerTransactionRepository;
import com.hardik.billing.ledger.dto.LedgerEntryData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class LedgerServiceTest {

    private final LedgerAccountRepository accounts = mock(LedgerAccountRepository.class);
    private final LedgerTransactionRepository transactions = mock(LedgerTransactionRepository.class);
    private final LedgerEntryRepository entries = mock(LedgerEntryRepository.class);
    private final LedgerService ledger = new LedgerService(accounts, transactions, entries);

    @Test
    void rejectsAnUnbalancedPostingBeforeAccessingRepositories() {
        var postings = List.of(new LedgerEntryData("CASH", LedgerDirection.DEBIT, 100L),
                new LedgerEntryData("ACCOUNTS_RECEIVABLE", LedgerDirection.CREDIT, 99L));

        assertThatThrownBy(() -> ledger.post(LedgerKind.PAYMENT_CAPTURED, UUID.randomUUID(), "USD", postings))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("equal debits and credits");
        verifyNoInteractions(accounts, transactions, entries);
    }

    @ParameterizedTest
    @CsvSource({"ASSET, 50", "REVENUE, -50", "CONTRA_REVENUE, 50"})
    void calculatesSignForEachAccountType(LedgerAccount.Type type, long balance) {
        var totals = new LedgerTotals();
        totals.add(LedgerDirection.DEBIT, 70L);
        totals.add(LedgerDirection.CREDIT, 20L);

        assertThat(totals.balance(type)).isEqualTo(balance);
    }

    @Test
    void rejectsAnOverflowingSumBeforeSaving() {
        var postings = List.of(new LedgerEntryData("CASH", LedgerDirection.DEBIT, Long.MAX_VALUE),
                new LedgerEntryData("CASH", LedgerDirection.DEBIT, 1L),
                new LedgerEntryData("ACCOUNTS_RECEIVABLE", LedgerDirection.CREDIT, Long.MAX_VALUE));

        assertThatThrownBy(() -> ledger.post(LedgerKind.PAYMENT_CAPTURED, UUID.randomUUID(), "USD", postings))
                .isInstanceOf(ArithmeticException.class);
        verifyNoInteractions(accounts, transactions, entries);
    }

    @Test
    void rejectsPostingWithNoEntriesEvenThoughItsTotalsMatch() {
        assertThatThrownBy(() -> ledger.post(LedgerKind.PAYMENT_CAPTURED, UUID.randomUUID(), "USD", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least two entries");
        verifyNoInteractions(accounts, transactions, entries);
    }

    @Test
    void rejectsBalancedZeroAmountPostingBeforeSaving() {
        var kind = LedgerKind.PAYMENT_CAPTURED;

        assertThatThrownBy(() -> ledger.post(kind, UUID.randomUUID(), "USD", kind.entries(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amounts must be positive");
        verifyNoInteractions(accounts, transactions, entries);
    }

    @Test
    void rejectsBalancedPostingInUnsupportedCurrencyBeforeSaving() {
        var kind = LedgerKind.PAYMENT_CAPTURED;

        assertThatThrownBy(() -> ledger.post(kind, UUID.randomUUID(), "EUR", kind.entries(100)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported ledger currency");
        verifyNoInteractions(accounts, transactions, entries);
    }
}
