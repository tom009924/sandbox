CREATE TABLE projects (
    id STRING(36) NOT NULL,
    name STRING(100) NOT NULL,
    description STRING(500),
    created_by STRING(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
) PRIMARY KEY (id);

CREATE TABLE project_members (
    project_id STRING(36) NOT NULL,
    user_id STRING(36) NOT NULL,
    role STRING(20) NOT NULL,
    joined_at TIMESTAMP NOT NULL,
) PRIMARY KEY (project_id, user_id);

CREATE INDEX idx_project_members_user ON project_members (user_id);
