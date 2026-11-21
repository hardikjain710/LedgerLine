package com.hardik.billing.reconciliation.dto;

import java.util.UUID;

public record ReconciliationDetail(String check, UUID entityId, String message) {
}
