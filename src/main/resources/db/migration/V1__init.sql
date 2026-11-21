CREATE TABLE customers (
  id UUID PRIMARY KEY,
  name VARCHAR(200) NOT NULL,
  email VARCHAR(320) NOT NULL UNIQUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE invoices (
  id UUID PRIMARY KEY,
  customer_id UUID NOT NULL REFERENCES customers(id),
  status VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT','OPEN','PAID','VOID','REFUNDED')),
  currency CHAR(3) NOT NULL CHECK (currency IN ('INR','USD')),
  total_amount BIGINT NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  finalized_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE invoice_line_items (
  id UUID PRIMARY KEY,
  invoice_id UUID NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
  description VARCHAR(500) NOT NULL,
  quantity INT NOT NULL CHECK (quantity > 0),
  unit_amount BIGINT NOT NULL CHECK (unit_amount >= 0),
  line_number INT NOT NULL
);

CREATE TABLE payments (
  id UUID PRIMARY KEY,
  invoice_id UUID NOT NULL REFERENCES invoices(id),
  amount BIGINT NOT NULL CHECK (amount > 0),
  currency CHAR(3) NOT NULL,
  status VARCHAR(20) NOT NULL CHECK (status IN ('CREATED','AUTHORIZED','CAPTURED','REFUNDED','FAILED','VOIDED')),
  processor VARCHAR(20) NOT NULL CHECK (processor IN ('SIMULATED','STRIPE')),
  processor_reference VARCHAR(255),
  payment_method_token VARCHAR(255) NOT NULL,
  failure_reason VARCHAR(255),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX one_active_payment_per_invoice
  ON payments(invoice_id) WHERE status IN ('CREATED','AUTHORIZED','CAPTURED');
CREATE INDEX idx_payments_processor_reference ON payments(processor_reference);

CREATE TABLE payment_status_history (
  id BIGSERIAL PRIMARY KEY,
  payment_id UUID NOT NULL REFERENCES payments(id),
  from_status VARCHAR(20),
  to_status VARCHAR(20) NOT NULL,
  reason VARCHAR(255),
  actor VARCHAR(20) NOT NULL CHECK (actor IN ('API','WEBHOOK','SYSTEM')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ledger_accounts (
  id SMALLINT PRIMARY KEY,
  code VARCHAR(40) NOT NULL UNIQUE,
  type VARCHAR(20) NOT NULL CHECK (type IN ('ASSET','REVENUE','CONTRA_REVENUE'))
);

CREATE TABLE ledger_transactions (
  id UUID PRIMARY KEY,
  kind VARCHAR(30) NOT NULL CHECK (kind IN ('INVOICE_FINALIZED','INVOICE_VOIDED','PAYMENT_CAPTURED','PAYMENT_REFUNDED')),
  reference_id UUID NOT NULL,
  currency CHAR(3) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (kind, reference_id)
);

CREATE TABLE ledger_entries (
  id BIGSERIAL PRIMARY KEY,
  transaction_id UUID NOT NULL REFERENCES ledger_transactions(id),
  account_id SMALLINT NOT NULL REFERENCES ledger_accounts(id),
  direction VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
  amount BIGINT NOT NULL CHECK (amount > 0)
);

CREATE TABLE idempotency_keys (
  id BIGSERIAL PRIMARY KEY,
  idempotency_key VARCHAR(255) NOT NULL,
  scope VARCHAR(255) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL CHECK (status IN ('IN_PROGRESS','COMPLETED')),
  response_status INT,
  response_body TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (idempotency_key, scope)
);

CREATE TABLE webhook_events (
  event_id VARCHAR(255) PRIMARY KEY,
  type VARCHAR(100) NOT NULL,
  payload JSONB NOT NULL,
  received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at TIMESTAMPTZ
);

CREATE TABLE reconciliation_reports (
  id UUID PRIMARY KEY,
  run_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  status VARCHAR(10) NOT NULL CHECK (status IN ('OK','MISMATCH')),
  details JSONB NOT NULL
);
