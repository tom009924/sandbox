CREATE TABLE users (
    id STRING(36) NOT NULL,
    username STRING(255) NOT NULL,
    password_hash STRING(255) NOT NULL,
) PRIMARY KEY (id);

CREATE UNIQUE INDEX idx_users_username ON users (username);

CREATE TABLE user_settings (
    user_id STRING(36) NOT NULL,
    default_priority STRING(20) NOT NULL,
    default_estimated_minutes INT64,
    max_active_todos INT64,
) PRIMARY KEY (user_id);
