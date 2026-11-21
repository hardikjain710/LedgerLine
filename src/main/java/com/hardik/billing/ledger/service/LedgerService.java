package com.hardik.billing.ledger.service;

import com.hardik.billing.ledger.LedgerAccountRepository;
import com.hardik.billing.ledger.LedgerDirection;
import com.hardik.billing.ledger.LedgerEntryRepository;
import com.hardik.billing.ledger.LedgerKind;
import com.hardik.billing.ledger.LedgerTotals;
import com.hardik.billing.ledger.LedgerTransaction;
import com.hardik.billing.ledger.LedgerTransactionRepository;
import com.hardik.billing.ledger.dto.LedgerBalanceResponse;
import com.hardik.billing.ledger.dto.LedgerEntryData;
import com.hardik.billing.ledger.dto.LedgerTransactionResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class LedgerService {

    public static final List<String> CURRENCIES = List.of("INR", "USD");

    private final LedgerAccountRepository accounts;
    private final LedgerTransactionRepository transactions;
    private final LedgerEntryRepository entries;

    public LedgerService(LedgerAccountRepository accounts, LedgerTransactionRepository transactions,
                         LedgerEntryRepository entries) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.entries = entries;
    }

    @Transactional
    public void post(LedgerKind kind, UUID referenceId, String currency, List<LedgerEntryData> postings) {
        if (postings.size() < 2) {
            throw new IllegalArgumentException("A ledger transaction requires at least two entries.");
        }
        var totals = new LedgerTotals();
        for (var entry : postings) {
            if (entry.amount() <= 0) {
                throw new IllegalArgumentException("Ledger entry amounts must be positive.");
            }
            totals.add(entry.direction(), entry.amount());
        }
        if (!totals.isBalanced()) {
            throw new IllegalArgumentException("Ledger transaction must have equal debits and credits.");
        }
        if (!CURRENCIES.contains(currency)) {
            throw new IllegalArgumentException("Unsupported ledger currency: " + currency);
        }

        var transaction = new LedgerTransaction(kind, referenceId, currency, Instant.now().truncatedTo(ChronoUnit.MICROS));
        for (var entry : postings) {
            var account = accounts.findByCode(entry.accountCode())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown ledger account: " + entry.accountCode()));
            transaction.addEntry(account, entry.direction(), entry.amount());
        }
        transactions.saveAndFlush(transaction);
    }

    public List<LedgerBalanceResponse> balances() {
        Map<AccountCurrency, LedgerTotals> totals = new HashMap<>();
        for (var row : entries.sumByAccountCurrencyAndDirection()) {
            var key = new AccountCurrency(row.getAccountCode(), row.getCurrency());
            totals.computeIfAbsent(key, ignored -> new LedgerTotals())
                    .add(LedgerDirection.valueOf(row.getDirection()), row.getAmount());
        }

        List<LedgerBalanceResponse> balances = new ArrayList<>();
        for (var account : accounts.findAllByOrderByCodeAsc()) {
            for (String currency : CURRENCIES) {
                var key = new AccountCurrency(account.getCode(), currency);
                var total = totals.get(key);
                long balance = total == null ? 0 : total.balance(account.getType());
                balances.add(new LedgerBalanceResponse(account.getCode(), currency, balance));
            }
        }
        return balances;
    }

    public List<LedgerTransactionResponse> transactions(UUID referenceId) {
        return transactions.findByReferenceIdOrderByCreatedAtAscIdAsc(referenceId).stream()
                .map(LedgerService::toResponse).toList();
    }

    public boolean hasPosting(LedgerKind kind, UUID referenceId) {
        return transactions.existsByKindAndReferenceId(kind, referenceId);
    }

    public List<LedgerTransactionResponse> allTransactions() {
        return transactions.findAllByOrderByCreatedAtAscIdAsc().stream().map(LedgerService::toResponse).toList();
    }

    private static LedgerTransactionResponse toResponse(LedgerTransaction transaction) {
        var postings = transaction.getEntries().stream()
                .map(entry -> new LedgerEntryData(entry.getAccount().getCode(), entry.getDirection(), entry.getAmount()))
                .toList();
        return new LedgerTransactionResponse(transaction.getId(), transaction.getKind(), transaction.getReferenceId(),
                transaction.getCurrency(), transaction.getCreatedAt(), postings);
    }

    private record AccountCurrency(String accountCode, String currency) {
    }
}
