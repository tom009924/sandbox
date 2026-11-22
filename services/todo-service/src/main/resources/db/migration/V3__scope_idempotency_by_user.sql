DROP INDEX idx_idempotency_expiration;
DROP TABLE idempotency_records;

CREATE TABLE idempotency_records (
    user_id STRING(36) NOT NULL,
    key STRING(255) NOT NULL,
    request_fingerprint STRING(64) NOT NULL,
    response_snapshot STRING(MAX) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
) PRIMARY KEY (user_id, key);

CREATE INDEX idx_idempotency_expiration ON idempotency_records (expires_at);
