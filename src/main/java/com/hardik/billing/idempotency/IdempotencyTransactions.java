package com.hardik.billing.idempotency;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyTransactions {

    private final IdempotencyRepository records;

    public IdempotencyTransactions(IdempotencyRepository records) {
        this.records = records;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long reserve(String key, String scope, String requestHash) {
        // A competing request must see the reservation while this request runs the business logic.
        return records.saveAndFlush(new IdempotencyRecord(key, scope, requestHash)).getId();
    }

    @Transactional
    public void complete(long id, int status, String body) {
        records.findById(id).orElseThrow().complete(status, body);
    }

    @Transactional
    public void discard(long id) {
        records.deleteById(id);
    }
}
