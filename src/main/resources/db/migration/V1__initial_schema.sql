CREATE TABLE users (
    id STRING(36) NOT NULL,
    username STRING(255) NOT NULL,
    password_hash STRING(255) NOT NULL,
) PRIMARY KEY (id);

CREATE UNIQUE INDEX idx_users_username ON users (username);

CREATE TABLE todos (
    id STRING(36) NOT NULL,
    user_id STRING(36) NOT NULL,
    title STRING(100) NOT NULL,
    completed BOOL NOT NULL,
    description STRING(500),
    due_date DATE,
    priority STRING(20) NOT NULL,
    estimated_minutes INT64,
) PRIMARY KEY (id);

CREATE INDEX idx_todos_user_completed ON todos (user_id, completed);
CREATE INDEX idx_todos_user_priority ON todos (user_id, priority);

CREATE TABLE user_settings (
    user_id STRING(36) NOT NULL,
    default_priority STRING(20) NOT NULL,
    default_estimated_minutes INT64,
    max_active_todos INT64,
) PRIMARY KEY (user_id);

CREATE TABLE idempotency_records (
    key STRING(255) NOT NULL,
    request_fingerprint STRING(64) NOT NULL,
    response_snapshot STRING(MAX) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
) PRIMARY KEY (key);

CREATE INDEX idx_idempotency_expiration ON idempotency_records (expires_at);

