package com.hardik.billing.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, Short> {

    Optional<LedgerAccount> findByCode(String code);

    List<LedgerAccount> findAllByOrderByCodeAsc();
}
