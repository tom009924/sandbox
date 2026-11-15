CREATE TABLE todo_subtasks (
    id STRING(36) NOT NULL,
    todo_id STRING(36) NOT NULL,
    title STRING(100) NOT NULL,
    completed BOOL NOT NULL,
    position INT64 NOT NULL,
) PRIMARY KEY (id);

CREATE INDEX idx_todo_subtasks_todo_position ON todo_subtasks (todo_id, position);
