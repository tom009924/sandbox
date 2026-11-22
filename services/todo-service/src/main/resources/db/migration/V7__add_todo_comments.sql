CREATE TABLE todo_comments (
    id STRING(36) NOT NULL,
    todo_id STRING(36) NOT NULL,
    user_id STRING(36) NOT NULL,
    content STRING(1000) NOT NULL,
    created_at TIMESTAMP NOT NULL,
) PRIMARY KEY (id);

CREATE INDEX idx_todo_comments_todo_created ON todo_comments (todo_id, created_at);
