# 外部接続順ダイアグラム（動的トレース自動生成）

> **自動生成ドキュメント**: 本ドキュメントは `./gradlew generateExternalConnectionDiagrams` により動的トレースから自動生成されています。手動編集は行わないでください。

| エンドポイント | トランザクション | 外部接続順 |
|---|---|---|
| `POST /api/auth/register` | readWrite | Spanner.UserRepository.existsByUsername → Spanner.UserRepository.save → Email.POST /api/v1/emails |
| `POST /api/auth/login` | readOnly | Spanner.UserRepository.findByUsername |
| `PUT /api/user/settings` | readWrite | Spanner.UserSettingsRepository.save |
| `GET /api/user/settings` | readOnly | Spanner.UserSettingsRepository.findByUserId |
| `POST /api/todos` | readWrite | Spanner.IdempotencyRecordRepository.findByUserIdAndKey → Spanner.UserSettingsRepository.findByUserId → Spanner.TodoRepository.countByUserIdAndCompletedFalse → KMS.Encrypt → Spanner.TodoRepository.save → KMS.Decrypt → PubSub.publish(CREATED) → Spanner.IdempotencyRecordRepository.save |
| `POST /api/todos (idempotent replay)` | readWrite | Spanner.IdempotencyRecordRepository.findByUserIdAndKey |
| `GET /api/todos` | readOnly | Spanner.TodoRepository.findAllByUserId → KMS.Decrypt |
| `GET /api/todos/{id}` | readOnly | Spanner.TodoRepository.findById → KMS.Decrypt |
| `PUT /api/todos/{id}` | readWrite | Spanner.TodoRepository.findById → KMS.Encrypt → Spanner.TodoRepository.save → KMS.Decrypt → PubSub.publish(UPDATED) |
| `PATCH /api/todos/{id} (reopen)` | readWrite | Spanner.TodoRepository.findById → Spanner.UserSettingsRepository.findByUserId → Spanner.TodoRepository.countByUserIdAndCompletedFalse → KMS.Encrypt → Spanner.TodoRepository.save → KMS.Decrypt → PubSub.publish(UPDATED) |
| `GET /api/admin/stats` | readOnly | Spanner.UserRepository.count → Spanner.TodoRepository.count → Spanner.TodoRepository.countByCompleted |
| `GET /api/admin/users` | readOnly | Spanner.UserRepository.findAll |
| `POST /api/todos/{id}/subtasks` | readWrite | Spanner.TodoRepository.findById → Spanner.TodoSubtaskRepository.findAllByTodoIdOrderByPositionAsc → Spanner.TodoSubtaskRepository.save |
| `GET /api/todos/{id}/subtasks` | readOnly | Spanner.TodoRepository.findById → Spanner.TodoSubtaskRepository.findAllByTodoIdOrderByPositionAsc |
| `PATCH /api/todos/{id}/subtasks/{subtaskId}` | readWrite | Spanner.TodoRepository.findById → Spanner.TodoSubtaskRepository.findById → Spanner.TodoSubtaskRepository.save |
| `DELETE /api/todos/{id}/subtasks/{subtaskId}` | readWrite | Spanner.TodoRepository.findById → Spanner.TodoSubtaskRepository.findById → Spanner.TodoSubtaskRepository.deleteById |
| `POST /api/todos/{id}/comments` | readWrite | Spanner.TodoRepository.findById → Spanner.TodoCommentRepository.save → Spanner.UserRepository.findById |
| `GET /api/todos/{id}/comments` | readOnly | Spanner.TodoRepository.findById → Spanner.TodoCommentRepository.findAllByTodoIdOrderByCreatedAtAsc → Spanner.UserRepository.findById |
| `DELETE /api/todos/{id}/comments/{commentId}` | readWrite | Spanner.TodoRepository.findById → Spanner.TodoCommentRepository.findById → Spanner.TodoCommentRepository.deleteById |
| `DELETE /api/todos/{id}` | readWrite | Spanner.TodoRepository.findById → Spanner.TodoRepository.delete → PubSub.publish(DELETED) |
| `POST /api/projects` | readWrite | Spanner.ProjectRepository.save → Spanner.ProjectMemberRepository.save |
| `GET /api/projects` | readOnly | Spanner.ProjectMemberRepository.findAllByUserId → Spanner.ProjectRepository.findAllByIdIn |
| `GET /api/projects/{projectId}` | readOnly | Spanner.ProjectMemberRepository.findByProjectIdAndUserId → Spanner.ProjectRepository.findById |
| `PUT /api/projects/{projectId}` | readWrite | Spanner.ProjectMemberRepository.findByProjectIdAndUserId → Spanner.ProjectRepository.findById → Spanner.ProjectRepository.save |
| `POST /api/projects/{projectId}/members` | readWrite | Spanner.ProjectMemberRepository.findByProjectIdAndUserId → Spanner.UserRepository.findByUsername → Spanner.ProjectMemberRepository.findByProjectIdAndUserId → Spanner.ProjectMemberRepository.save |
| `GET /api/projects/{projectId}/members` | readOnly | Spanner.ProjectMemberRepository.findByProjectIdAndUserId → Spanner.ProjectMemberRepository.findAllByProjectId → Spanner.UserRepository.findAllByIdIn |
| `DELETE /api/projects/{projectId}/members/{userId}` | readWrite | Spanner.ProjectMemberRepository.findByProjectIdAndUserId → Spanner.ProjectMemberRepository.findByProjectIdAndUserId → Spanner.ProjectMemberRepository.deleteByProjectIdAndUserId |
| `DELETE /api/projects/{projectId}` | readWrite | Spanner.ProjectMemberRepository.findByProjectIdAndUserId → Spanner.ProjectRepository.findById → Spanner.TodoRepository.deleteAllByProjectId → Spanner.ProjectMemberRepository.deleteAllByProjectId → Spanner.ProjectRepository.deleteById |

## POST /api/auth/register

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant App as Todo App
    participant Spanner as Cloud Spanner
    participant Email as Email API (HTTP)
    Client->>App: POST /api/auth/register
    App->>Spanner: UserRepository.existsByUsername
    App->>Spanner: UserRepository.save
    App->>Email: POST /api/v1/emails
    App->>Spanner: Commit (@Transactional)
    App-->>Client: Response
```

## POST /api/auth/login

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant App as Todo App
    participant Spanner as Cloud Spanner
    Client->>App: POST /api/auth/login
    App->>Spanner: UserRepository.findByUsername
    App-->>Client: Response
```

## PUT /api/user/settings

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant App as Todo App
    participant Spanner as Cloud Spanner
    Client->>App: PUT /api/user/settings
    App->>Spanner: UserSettingsRepository.save
    App->>Spanner: Commit (@Transactional)
    App-->>Client: Response
```

## GET /api/user/settings

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant App as Todo App
    participant Spanner as Cloud Spanner
    Client->>App: GET /api/user/settings
    App->>Spanner: UserSettingsRepository.findByUserId
    App-->>Client: Response
```

## POST /api/todos

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant App as Todo App
    participant Spanner as Cloud Spanner
    participant KMS as KMS (gRPC)
    participant PubSub as Cloud Pub/Sub
    Client->>App: POST /api/todos
    App->>Spanner: IdempotencyRecordRepository.findByUserIdAndKey
    App->>Spanner: UserSettingsRepository.findByUserId
    App->>Spanner: TodoRepository.countByUserIdAndCompletedFalse
    App->>KMS: Encrypt
    App->>Spanner: TodoRepository.save
    App->>KMS: Decrypt
    App-->>PubSub: publish(CREATED)
    App->>Spanner: IdempotencyRecordRepository.save
    App->>Spanner: Commit (@Transactional)
    App-->>Client: Response
```

## POST /api/todos (idempotent replay)

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant App as Todo App
    participant Spanner as Cloud Spanner
    Client->>App: POST /api/todos (idempotent replay)
    App->>Spanner: IdempotencyRecordRepository.findByUserIdAndKey
    App->>Spanner: Commit (@Transactional)
    App-->>Client: Response
```

## GET /api/todos

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant App as Todo App
    participant Spanner as Cloud Spanner
    participant KMS as KMS (gRPC)
    Client->>App: GET /api/todos
    App->>Spanner: TodoRepository.findAllByUserId
    App->>KMS: Decrypt
    App-->>Client: Response
```

## GET /api/todos/{id}

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant App as Todo App
    participant Spanner as Cloud Spanner
    participant KMS as KMS (gRPC)
    Client->>App: GET /api/todos/{id}
    App->>Spanner: TodoRepository.findById
    App->>KMS: Decrypt
    App-->>Client: Response
```

## PUT /api/todos/{id}

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant App as Todo App
    participant Spanner as Cloud Spanner
    participant KMS as KMS (gRPC)
    participant PubSub as Cloud Pub/Sub
    Client->>App: PUT /api/todos/{id}
    App->>Spanner: TodoRepository.findById
    App->>KMS: Encrypt
    App->>Spanner: TodoRepository.save
    App->>KMS: Decrypt
    App-->>PubSub: publish(UPDATED)
    App->>Spanner: Commit (@Transactional)
    App-->>Client: Response
```

## PATCH /api/todos/{id} (reopen)

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant App as Todo App
    participant Spanner as Cloud Spanner
    participant KMS as KMS (gRPC)
    participant PubSub as Cloud Pub/Sub
    Client->>App: PATCH /api/todos/{id} (reopen)
    App->>Spanner: TodoRepository.findById
    App->>Spanner: UserSettingsRepository.findByUserId
    App->>Spanner: TodoRepository.countByUserIdAndCompletedFalse
    App->>KMS: Encrypt
    App->>Spanner: TodoRepository.save
    App->>KMS: Decrypt
    App-->>PubSub: publish(UPDATED)
    App->>Spanner: Commit (@Transactional)
    App-->>Client: Response
```

## GET /api/admin/stats

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant App as Todo App
    participant Spanner as Cloud Spanner
    Client->>App: GET /api/admin/stats
    App->>Spanner: UserRepository.count
    App->>Spanner: TodoRepository.count
    App->>Spanner: TodoRepository.countByCompleted
    App-->>Client: Response
```

## GET /api/admin/users

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant App as Todo App
    participant Spanner as Cloud Spanner
    Client->>App: GET /api/admin/users
    App->>Spanner: UserRepository.findAll
    App-->>Client: Response
```

## POST /api/todos/{id}/subtasks

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant App as Todo App
    participant Spanner as Cloud Spanner
    Client->>App: POST /api/todos/{id}/subtasks
    App->>Spanner: TodoRepository.findById
    App->>Spanner: TodoSubtaskRepository.findAllByTodoIdOrderByPositionAsc
    App->>Spanner: TodoSubtaskRepository.save
    App->>Spanner: Commit (@Transactional)
    App-->>Client: Response
```

## GET /api/todos/{id}/subtasks

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant App as Todo App
    participant Spanner as Cloud Spanner
    Client->>App: GET /api/todos/{id}/subtasks
    App->>Spanner: TodoRepository.findById
    App->>Spanner: TodoSubtaskRepository.findAllByTodoIdOrderByPositionAsc
    App-->>Client: Response
```

## PATCH /api/todos/{id}/subtasks/{subtaskId}

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant App as Todo App
    participant Spanner as Cloud Spanner
    Client->>App: PATCH /api/todos/{id}/subtasks/{subtaskId}
    App->>Spanner: TodoRepository.findById
    App->>Spanner: TodoSubtaskRepository.findById
    App->>Spanner: TodoSubtaskRepository.save
    App->>Spanner: Commit (@Transactional)
    App-->>Client: Response
```

## DELETE /api/todos/{id}/subtasks/{subtaskId}

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant App as Todo App
    participant Spanner as Cloud Spanner
    Client->>App: DELETE /api/todos/{id}/subtasks/{subtaskId}
    App->>Spanner: TodoRepository.findById
    App->>Spanner: TodoSubtaskRepository.findById
    App->>Spanner: TodoSubtaskRepository.deleteById
    App->>Spanner: Commit (@Transactional)
    App-->>Client: Response
```

## POST /api/todos/{id}/comments

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant App as Todo App
    participant Spanner as Cloud Spanner
    Client->>App: POST /api/todos/{id}/comments
    App->>Spanner: TodoRepository.findById
    App->>Spanner: TodoCommentRepository.save
    App->>Spanner: UserRepository.findById
    App->>Spanner: Commit (@Transactional)
    App-->>Client: Response
```

## GET /api/todos/{id}/comments

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant App as Todo App
    participant Spanner as Cloud Spanner
    Client->>App: GET /api/todos/{id}/comments
    App->>Spanner: TodoRepository.findById
    App->>Spanner: TodoCommentRepository.findAllByTodoIdOrderByCreatedAtAsc
    App->>Spanner: UserRepository.findById
    App-->>Client: Response
```

## DELETE /api/todos/{id}/comments/{commentId}

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant App as Todo App
    participant Spanner as Cloud Spanner
    Client->>App: DELETE /api/todos/{id}/comments/{commentId}
    App->>Spanner: TodoRepository.findById
    App->>Spanner: TodoCommentRepository.findById
    App->>Spanner: TodoCommentRepository.deleteById
    App->>Spanner: Commit (@Transactional)
    App-->>Client: Response
```

## DELETE /api/todos/{id}

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant App as Todo App
    participant Spanner as Cloud Spanner
    participant PubSub as Cloud Pub/Sub
    Client->>App: DELETE /api/todos/{id}
    App->>Spanner: TodoRepository.findById
    App->>Spanner: TodoRepository.delete
    App-->>PubSub: publish(DELETED)
    App->>Spanner: Commit (@Transactional)
    App-->>Client: Response
```

## POST /api/projects

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant App as Todo App
    participant Spanner as Cloud Spanner
    Client->>App: POST /api/projects
    App->>Spanner: ProjectRepository.save
    App->>Spanner: ProjectMemberRepository.save
    App->>Spanner: Commit (@Transactional)
    App-->>Client: Response
```

## GET /api/projects

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant App as Todo App
    participant Spanner as Cloud Spanner
    Client->>App: GET /api/projects
    App->>Spanner: ProjectMemberRepository.findAllByUserId
    App->>Spanner: ProjectRepository.findAllByIdIn
    App-->>Client: Response
```

## GET /api/projects/{projectId}

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant App as Todo App
    participant Spanner as Cloud Spanner
    Client->>App: GET /api/projects/{projectId}
    App->>Spanner: ProjectMemberRepository.findByProjectIdAndUserId
    App->>Spanner: ProjectRepository.findById
    App-->>Client: Response
```

## PUT /api/projects/{projectId}

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant App as Todo App
    participant Spanner as Cloud Spanner
    Client->>App: PUT /api/projects/{projectId}
    App->>Spanner: ProjectMemberRepository.findByProjectIdAndUserId
    App->>Spanner: ProjectRepository.findById
    App->>Spanner: ProjectRepository.save
    App->>Spanner: Commit (@Transactional)
    App-->>Client: Response
```

## POST /api/projects/{projectId}/members

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant App as Todo App
    participant Spanner as Cloud Spanner
    Client->>App: POST /api/projects/{projectId}/members
    App->>Spanner: ProjectMemberRepository.findByProjectIdAndUserId
    App->>Spanner: UserRepository.findByUsername
    App->>Spanner: ProjectMemberRepository.findByProjectIdAndUserId
    App->>Spanner: ProjectMemberRepository.save
    App->>Spanner: Commit (@Transactional)
    App-->>Client: Response
```

## GET /api/projects/{projectId}/members

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant App as Todo App
    participant Spanner as Cloud Spanner
    Client->>App: GET /api/projects/{projectId}/members
    App->>Spanner: ProjectMemberRepository.findByProjectIdAndUserId
    App->>Spanner: ProjectMemberRepository.findAllByProjectId
    App->>Spanner: UserRepository.findAllByIdIn
    App-->>Client: Response
```

## DELETE /api/projects/{projectId}/members/{userId}

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant App as Todo App
    participant Spanner as Cloud Spanner
    Client->>App: DELETE /api/projects/{projectId}/members/{userId}
    App->>Spanner: ProjectMemberRepository.findByProjectIdAndUserId
    App->>Spanner: ProjectMemberRepository.findByProjectIdAndUserId
    App->>Spanner: ProjectMemberRepository.deleteByProjectIdAndUserId
    App->>Spanner: Commit (@Transactional)
    App-->>Client: Response
```

## DELETE /api/projects/{projectId}

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant App as Todo App
    participant Spanner as Cloud Spanner
    Client->>App: DELETE /api/projects/{projectId}
    App->>Spanner: ProjectMemberRepository.findByProjectIdAndUserId
    App->>Spanner: ProjectRepository.findById
    App->>Spanner: TodoRepository.deleteAllByProjectId
    App->>Spanner: ProjectMemberRepository.deleteAllByProjectId
    App->>Spanner: ProjectRepository.deleteById
    App->>Spanner: Commit (@Transactional)
    App-->>Client: Response
```

