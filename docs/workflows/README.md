# E2E ワークフロー仕様・可視化ダイアグラム (Arazzo Workflows)

> **自動生成ドキュメント**: 本ドキュメントは `docs/workflows/*.arazzo.yaml` からスクリプト（`npm run generate:diagrams`）により自動生成されています。手動編集は行わず、Arazzo ファイルを直接更新してください。

> **Arazzo UI でのインタラクティブ閲覧**: `npm run arazzo:ui` を実行すると、ブラウザ上でステップ詳細やデータフローをインタラクティブに確認できます。

## Todo API Workflows

End-to-end user workflows and operational journeys for the Todo API.

### ワークフロー: `user-todo-lifecycle`
**User registration, login, and Todo management lifecycle**

Demonstrates registering a new user, authenticating with JWT, inspecting default settings, updating settings, creating an idempotent todo, fetching, updating, listing, and deleting it.

#### シーケンス図 (Sequence Diagram)
```mermaid
sequenceDiagram
    autonumber
    actor Client as Client / Runner
    participant API as Todo API
    Client->>API: registerUser (registerUser): Register a new end-user account
    API-->>Client: 201 Created [outputs: userId, userName]
    Client->>API: loginUser (loginUser): Authenticate user credentials and acquire JWT token
    API-->>Client: 200 OK [outputs: jwtToken]
    Client->>API: getUserSettings (getUserSettings): Fetch initial default user settings
    API-->>Client: 200 OK
    Client->>API: updateUserSettings (updateUserSettings): Customize user settings for default priority and duration
    API-->>Client: 200 OK
    Client->>API: createTodo (createTodo): Create a new Todo item with an idempotency key
    API-->>Client: 201 Created [outputs: createdTodoId]
    Client->>API: getTodo (getTodo): Fetch the created Todo item by ID
    API-->>Client: 200 OK
    Client->>API: updateTodo (updateTodo): Mark the Todo item as completed
    API-->>Client: 200 OK
    Client->>API: listTodos (listTodos): List all Todos for the authenticated user
    API-->>Client: 200 OK
    Client->>API: patchTodo (patchTodo): Partially update the Todo priority
    API-->>Client: 200 OK
    Client->>API: deleteTodo (deleteTodo): Delete the completed Todo item
    API-->>Client: 204 No Content
```

#### ステップ詳細一覧
| # | Step ID | Operation | 概要 | 期待ステータス | 出力 (Outputs) |
| :-: | :--- | :--- | :--- | :---: | :--- |
| 1 | `registerUser` | `registerUser` | Register a new end-user account | `201 Created` | `userId`, `userName` |
| 2 | `loginUser` | `loginUser` | Authenticate user credentials and acquire JWT token | `200 OK` | `jwtToken` |
| 3 | `getUserSettings` | `getUserSettings` | Fetch initial default user settings | `200 OK` | - |
| 4 | `updateUserSettings` | `updateUserSettings` | Customize user settings for default priority and duration | `200 OK` | - |
| 5 | `createTodo` | `createTodo` | Create a new Todo item with an idempotency key | `201 Created` | `createdTodoId` |
| 6 | `getTodo` | `getTodo` | Fetch the created Todo item by ID | `200 OK` | - |
| 7 | `updateTodo` | `updateTodo` | Mark the Todo item as completed | `200 OK` | - |
| 8 | `listTodos` | `listTodos` | List all Todos for the authenticated user | `200 OK` | - |
| 9 | `patchTodo` | `patchTodo` | Partially update the Todo priority | `200 OK` | - |
| 10 | `deleteTodo` | `deleteTodo` | Delete the completed Todo item | `204 No Content` | - |

---

### ワークフロー: `admin-audit-lifecycle`
**Administrator system audit**

Inspect system statistics and registered users using administrator API key.

#### シーケンス図 (Sequence Diagram)
```mermaid
sequenceDiagram
    autonumber
    actor Client as Client / Runner
    participant API as Todo API
    Client->>API: getAdminStats (getAdminStats): Fetch system-wide usage statistics
    API-->>Client: 200 OK
    Client->>API: getAdminUsers (getAdminUsers): Fetch registered users directory
    API-->>Client: 200 OK
```

#### ステップ詳細一覧
| # | Step ID | Operation | 概要 | 期待ステータス | 出力 (Outputs) |
| :-: | :--- | :--- | :--- | :---: | :--- |
| 1 | `getAdminStats` | `getAdminStats` | Fetch system-wide usage statistics | `200 OK` | - |
| 2 | `getAdminUsers` | `getAdminUsers` | Fetch registered users directory | `200 OK` | - |

---
