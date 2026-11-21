package com.hardik.billing.ledger;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {

    boolean existsByKindAndReferenceId(LedgerKind kind, UUID referenceId);

    @EntityGraph(attributePaths = {"entries", "entries.account"})
    List<LedgerTransaction> findByReferenceIdOrderByCreatedAtAscIdAsc(UUID referenceId);

    @EntityGraph(attributePaths = {"entries", "entries.account"})
    List<LedgerTransaction> findAllByOrderByCreatedAtAscIdAsc();
}
