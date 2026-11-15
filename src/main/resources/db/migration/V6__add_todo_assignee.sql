ALTER TABLE todos ADD COLUMN assignee_id STRING(36);

CREATE INDEX idx_todos_project_assignee ON todos (project_id, assignee_id);
