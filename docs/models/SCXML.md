# モデルベースドテスト状態遷移仕様 (W3C SCXML)

> **自動生成ドキュメント**: 本ドキュメントは `docs/models/*.scxml` からスクリプト（`npm run generate:scxml-diagrams`）により自動生成されています。手動編集は行わず、SCXML ファイルを直接更新してください。

## TodoLifecycleModel (`todo-lifecycle.scxml`)

- **初期状態**: `NotCreated`
- **対象 OpenAPI `operationId` (12件)**: `createTodo`, `createTodoSubtask`, `deleteTodo`, `deleteTodoSubtask`, `getTodo`, `getUserSettings`, `listTodoSubtasks`, `listTodos`, `patchTodo`, `patchTodoSubtask`, `updateTodo`, `updateUserSettings`

```mermaid
stateDiagram-v2
    [*] --> NotCreated
    NotCreated --> NotCreated: getUserSettings
    NotCreated --> NotCreated: updateUserSettings
    NotCreated --> NotCreated: listTodos
    NotCreated --> NotCreated: getTodo [notExists]
    NotCreated --> NotCreated: updateTodo [notExists]
    NotCreated --> NotCreated: patchTodo [notExists]
    NotCreated --> NotCreated: deleteTodo [notExists]
    NotCreated --> NotCreated: listTodoSubtasks [notExists]
    NotCreated --> NotCreated: createTodoSubtask [notExists]
    NotCreated --> Active: createTodo [activeCount < maxActiveTodos]
    Active --> Active: getUserSettings
    Active --> Active: updateUserSettings
    Active --> Active: getTodo
    Active --> Active: listTodos
    Active --> Active: createTodo [idempotencyReplay]
    Active --> Active: createTodo [idempotencyConflict]
    Active --> Active: createTodo [activeCount >= maxActiveTodos]
    Active --> Active: updateTodo [completed == false]
    Active --> Completed: updateTodo [completed == true]
    Active --> Active: patchTodo [completed == false]
    Active --> Completed: patchTodo [completed == true]
    Active --> Active: listTodoSubtasks
    Active --> Active: createTodoSubtask
    Active --> Active: patchTodoSubtask [subtaskCount > 0]
    Active --> Active: deleteTodoSubtask [subtaskCount > 0]
    Active --> Deleted: deleteTodo
    Completed --> Completed: getUserSettings
    Completed --> Completed: updateUserSettings
    Completed --> Completed: getTodo
    Completed --> Completed: listTodos
    Completed --> Active: updateTodo [completed == false && activeCount < maxActiveTodos]
    Completed --> Completed: updateTodo [completed == true]
    Completed --> Active: patchTodo [completed == false && activeCount < maxActiveTodos]
    Completed --> Completed: patchTodo [completed == true]
    Completed --> Completed: listTodoSubtasks
    Completed --> Completed: createTodoSubtask
    Completed --> Completed: patchTodoSubtask [subtaskCount > 0]
    Completed --> Completed: deleteTodoSubtask [subtaskCount > 0]
    Completed --> Deleted: deleteTodo
    Deleted --> Deleted: listTodos
    Deleted --> Deleted: getTodo [notExists]
    Deleted --> Deleted: updateTodo [notExists]
    Deleted --> Deleted: patchTodo [notExists]
    Deleted --> Deleted: deleteTodo [notExists]
    Deleted --> Deleted: listTodoSubtasks [notExists]
    Deleted --> Deleted: createTodoSubtask [notExists]
```
