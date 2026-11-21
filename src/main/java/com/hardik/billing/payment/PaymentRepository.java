package com.hardik.billing.payment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    interface Location {
        UUID getId();

        UUID getInvoiceId();
    }

    Optional<Location> findLocationById(UUID id);

    Optional<Location> findLocationByProcessorReference(String processorReference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select payment from Payment payment where payment.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") UUID id);

    boolean existsByInvoiceIdAndStatusIn(UUID invoiceId, Collection<PaymentStatus> statuses);

    List<Payment> findAllByStatusInOrderByIdAsc(Collection<PaymentStatus> statuses);
}
