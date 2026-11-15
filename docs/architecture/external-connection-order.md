# 外部接続順ダイアグラム（動的トレース自動生成）

> **自動生成ドキュメント**: 本ドキュメントは `./gradlew generateExternalConnectionDiagrams` により動的トレースから自動生成されています。手動編集は行わないでください。

| エンドポイント | トランザクション | 外部接続順 |
|---|---|---|
| `POST /api/auth/register` | readWrite | Spanner.UserRepository.existsByUsername → Spanner.UserRepository.save → Email.POST /api/v1/emails |
| `POST /api/auth/login` | readOnly | Spanner.UserRepository.findByUsername |
| `PUT /api/settings` | readWrite | Spanner.UserSettingsRepository.save |
| `GET /api/settings` | readOnly | Spanner.UserSettingsRepository.findByUserId |
| `POST /api/todos` | readWrite | Spanner.IdempotencyRecordRepository.findByUserIdAndKey → Spanner.UserSettingsRepository.findByUserId → Spanner.TodoRepository.countByUserIdAndCompletedFalse → KMS.Encrypt → Spanner.TodoRepository.save → KMS.Decrypt → PubSub.publish(CREATED) → Spanner.IdempotencyRecordRepository.save |
| `POST /api/todos (idempotent replay)` | readWrite | Spanner.IdempotencyRecordRepository.findByUserIdAndKey |
| `GET /api/todos` | readOnly | Spanner.TodoRepository.findAllByUserId → KMS.Decrypt |
| `GET /api/todos/{id}` | readOnly | Spanner.TodoRepository.findById → KMS.Decrypt |
| `PUT /api/todos/{id}` | readWrite | Spanner.TodoRepository.findById → KMS.Encrypt → Spanner.TodoRepository.save → KMS.Decrypt → PubSub.publish(UPDATED) |
| `PATCH /api/todos/{id} (reopen)` | readWrite | Spanner.TodoRepository.findById → Spanner.UserSettingsRepository.findByUserId → Spanner.TodoRepository.countByUserIdAndCompletedFalse → KMS.Encrypt → Spanner.TodoRepository.save → KMS.Decrypt → PubSub.publish(UPDATED) |
| `GET /api/admin/stats` | readOnly | Spanner.UserRepository.count → Spanner.TodoRepository.count → Spanner.TodoRepository.countByCompleted |
| `GET /api/admin/users` | readOnly | Spanner.UserRepository.findAll |
| `DELETE /api/todos/{id}` | readWrite | Spanner.TodoRepository.findById → Spanner.TodoRepository.delete → PubSub.publish(DELETED) |

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

## PUT /api/settings

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant App as Todo App
    participant Spanner as Cloud Spanner
    Client->>App: PUT /api/settings
    App->>Spanner: UserSettingsRepository.save
    App->>Spanner: Commit (@Transactional)
    App-->>Client: Response
```

## GET /api/settings

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant App as Todo App
    participant Spanner as Cloud Spanner
    Client->>App: GET /api/settings
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

