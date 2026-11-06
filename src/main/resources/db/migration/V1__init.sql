-- All money is stored as BIGINT cents. Never floating point.

CREATE TABLE account (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    holder_name    VARCHAR(200) NOT NULL,
    email          VARCHAR(320) NOT NULL,
    date_of_birth  DATE         NOT NULL,
    balance_cents  BIGINT       NOT NULL DEFAULT 0,
    created_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_account_email UNIQUE (email),
    -- Last line of defence: even a buggy code path can never drive the balance negative.
    CONSTRAINT ck_account_balance_non_negative CHECK (balance_cents >= 0)
) ENGINE = InnoDB;

CREATE TABLE card (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    account_id  BIGINT      NOT NULL,
    last4       CHAR(4)     NOT NULL,
    expiry      DATE        NOT NULL,
    status      VARCHAR(16) NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    -- MySQL has no partial unique index, so this generated column is account_id for ACTIVE
    -- cards and NULL otherwise. UNIQUE ignores NULLs, so at most one ACTIVE card per account.
    active_account_id BIGINT AS (CASE WHEN status = 'ACTIVE' THEN account_id END) STORED,
    PRIMARY KEY (id),
    CONSTRAINT fk_card_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT uq_card_one_active_per_account UNIQUE (active_account_id),
    CONSTRAINT ck_card_status CHECK (status IN ('ACTIVE', 'FROZEN'))
) ENGINE = InnoDB;

-- Named hsa_transaction because TRANSACTION is a MySQL keyword.
CREATE TABLE hsa_transaction (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    account_id         BIGINT       NOT NULL,
    card_id            BIGINT       NULL,
    type               VARCHAR(16)  NOT NULL,
    amount_cents       BIGINT       NOT NULL,
    merchant_name      VARCHAR(200) NULL,
    merchant_category  VARCHAR(32)  NULL,
    status             VARCHAR(16)  NOT NULL,
    decline_reason     VARCHAR(32)  NULL,
    created_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_tx_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT fk_tx_card FOREIGN KEY (card_id) REFERENCES card (id),
    CONSTRAINT ck_tx_amount_positive CHECK (amount_cents > 0),
    CONSTRAINT ck_tx_type CHECK (type IN ('DEPOSIT', 'PURCHASE')),
    CONSTRAINT ck_tx_status CHECK (status IN ('APPROVED', 'DECLINED')),
    CONSTRAINT ck_tx_decline_reason CHECK (
        (status = 'APPROVED' AND decline_reason IS NULL) OR
        (status = 'DECLINED' AND decline_reason IN ('CARD_INACTIVE', 'NOT_QUALIFIED', 'INSUFFICIENT_FUNDS'))
    )
) ENGINE = InnoDB;

CREATE INDEX ix_tx_account_created ON hsa_transaction (account_id, created_at);
