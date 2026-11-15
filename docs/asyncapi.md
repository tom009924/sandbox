# 非同期イベント仕様・可視化ダイアグラム (Todo Service Events v1.0.0)

> **自動生成ドキュメント**: 本ドキュメントは `docs/asyncapi.yaml` からスクリプト（`npm run generate:asyncapi-diagrams`）により自動生成されています。手動編集は行わず、`docs/asyncapi.yaml` を直接更新してください。

Asynchronous domain events published by the Todo Service to Google Cloud Pub/Sub.
Derived from `com.example.todo.domain.event.TodoEvent` and `PubSubTodoEventPublisher`.

## イベントフロー図

```mermaid
flowchart LR
    Producer["Todo Service Events"]
    Channel_todoEvents["Channel: todoEvents<br/>projects/{projectId}/topics/todo-events<br/>[gcp-pubsub (googlepubsub)]"]
    Producer -->|publishTodoEvent (send: todoEvent)| Channel_todoEvents
```

## チャネル & オペレーション一覧

| Operation | Action | Channel | Address | Summary |
| :--- | :--- | :--- | :--- | :--- |
| `publishTodoEvent` | `send` | `todoEvents` | `projects/{projectId}/topics/todo-events` | Publish Todo lifecycle event (CREATED, UPDATED, DELETED) |

## スキーマ一覧

### `TodoEvent`

| Property | Type | Format | Required | Nullable |
| :--- | :--- | :--- | :---: | :---: |
| `eventId` | `string` | `uuid` | Yes | No |
| `eventType` | `string` | - | Yes | No |
| `timestamp` | `string` | `date-time` | Yes | No |
| `todoId` | `string` | `uuid` | Yes | No |
| `todo` | `$ref: TodoPayload` | - | No | Yes |

### `TodoPayload`

| Property | Type | Format | Required | Nullable |
| :--- | :--- | :--- | :---: | :---: |
| `id` | `string` | `uuid` | Yes | No |
| `userId` | `string` | `uuid` | Yes | No |
| `title` | `string` | - | Yes | No |
| `completed` | `boolean` | - | Yes | No |
| `description` | `string` | - | No | Yes |
| `dueDate` | `string` | `date` | No | Yes |
| `priority` | `string` | - | Yes | No |
| `estimatedMinutes` | `integer` | - | No | Yes |
| `tags` | `array` | - | Yes | No |
| `projectId` | `string` | `uuid` | No | Yes |
| `assigneeId` | `string` | `uuid` | No | Yes |
