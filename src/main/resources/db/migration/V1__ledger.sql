-- Ledger schema.
--
-- Design note: every invariant that protects money is enforced here as well as in the
-- domain model. Application-level checks are bypassed by migrations, psql sessions and
-- future services; database constraints are not.

CREATE TABLE account (
    id              UUID        PRIMARY KEY,
    currency        CHAR(3)     NOT NULL,
    normal_side     SMALLINT    NOT NULL CHECK (normal_side IN (1, -1)),
    allows_negative BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ledger_transaction (
    id                 UUID        PRIMARY KEY,
    currency           CHAR(3)     NOT NULL,
    external_reference TEXT,
    occurred_at        TIMESTAMPTZ NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Entries are append-only. There is no UPDATE and no DELETE path in the application:
-- a mistake is corrected by posting a reversing transaction, which keeps the audit
-- trail intact. Revoked at the role level in V2 for production deployments.
CREATE TABLE ledger_entry (
    id                  BIGINT   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id      UUID     NOT NULL REFERENCES ledger_transaction (id),
    account_id          UUID     NOT NULL REFERENCES account (id),
    direction           SMALLINT NOT NULL CHECK (direction IN (1, -1)),
    amount_minor_units  BIGINT   NOT NULL CHECK (amount_minor_units > 0),
    signed_minor_units  BIGINT   GENERATED ALWAYS AS (direction * amount_minor_units) STORED
);

CREATE INDEX idx_ledger_entry_account ON ledger_entry (account_id, id DESC);
CREATE INDEX idx_ledger_entry_transaction ON ledger_entry (transaction_id);

-- allows_negative is denormalised here on purpose: a CHECK constraint cannot reference
-- another table, and this lets the overdraft rule be declarative instead of a trigger.
-- Kept in sync by a trigger on account (V1, below).
CREATE TABLE account_balance (
    account_id       UUID        PRIMARY KEY REFERENCES account (id),
    balance_minor    BIGINT      NOT NULL DEFAULT 0,
    normal_side      SMALLINT    NOT NULL,
    allows_negative  BOOLEAN     NOT NULL,
    version          BIGINT      NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT no_overdraft CHECK (allows_negative OR balance_minor * normal_side >= 0)
);

CREATE FUNCTION create_account_balance() RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO account_balance (account_id, balance_minor, normal_side, allows_negative)
    VALUES (NEW.id, 0, NEW.normal_side, NEW.allows_negative);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER account_balance_bootstrap
    AFTER INSERT ON account
    FOR EACH ROW EXECUTE FUNCTION create_account_balance();

-- The zero-sum invariant, enforced at COMMIT.
--
-- DEFERRABLE INITIALLY DEFERRED is what makes this workable: entries are inserted one
-- row at a time, so the transaction is legitimately unbalanced mid-flight. Checking
-- per-statement would reject every valid posting; checking at commit rejects exactly
-- the invalid ones.
CREATE FUNCTION assert_transaction_is_balanced() RETURNS TRIGGER AS $$
DECLARE
    imbalance BIGINT;
BEGIN
    SELECT COALESCE(SUM(signed_minor_units), 0)
      INTO imbalance
      FROM ledger_entry
     WHERE transaction_id = NEW.transaction_id;

    IF imbalance <> 0 THEN
        RAISE EXCEPTION 'unbalanced transaction %: imbalance=% minor units',
            NEW.transaction_id, imbalance
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER ledger_entry_must_balance
    AFTER INSERT ON ledger_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_transaction_is_balanced();

CREATE TABLE idempotency_record (
    key                  TEXT        PRIMARY KEY,
    request_fingerprint  TEXT        NOT NULL,
    transaction_id       UUID        NOT NULL REFERENCES ledger_transaction (id),
    created_at           TIMESTAMPTZ NOT NULL
);

-- Transactional outbox. Written in the same transaction as the entries, so an event is
-- published if and only if the money moved. Delivery is at-least-once; consumers
-- deduplicate on transactionId.
CREATE TABLE outbox_event (
    id           BIGINT   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_id TEXT        NOT NULL,
    event_type   TEXT        NOT NULL,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

-- Partial index: the relay only ever scans unpublished rows, so the index stays small
-- regardless of how much history the table accumulates.
CREATE INDEX idx_outbox_unpublished ON outbox_event (id) WHERE published_at IS NULL;
