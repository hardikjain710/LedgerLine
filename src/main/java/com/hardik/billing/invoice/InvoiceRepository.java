package com.hardik.billing.invoice;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID>, JpaSpecificationExecutor<Invoice> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select invoice from Invoice invoice where invoice.id = :id")
    Optional<Invoice> findByIdForUpdate(@Param("id") UUID id);

    List<Invoice> findAllByStatusInOrderByIdAsc(Collection<InvoiceStatus> statuses);
}
