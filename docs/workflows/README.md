# E2E ワークフロー仕様・可視化ダイアグラム (Arazzo Workflows)

> **自動生成ドキュメント**: 本ドキュメントは `docs/workflows/*.arazzo.yaml` からスクリプト（`npm run generate:diagrams`）により自動生成されています。手動編集は行わず、Arazzo ファイルを直接更新してください。

> **Arazzo UI でのインタラクティブ閲覧**: `npm run arazzo:ui` を実行すると、ブラウザ上でステップ詳細やデータフローをインタラクティブに確認できます。

## Todo API Workflows

End-to-end user workflows and operational journeys for the Todo API.

### ワークフロー: `user-todo-lifecycle`
**User registration, login, and Todo management lifecycle**

Demonstrates registering a new user, authenticating with JWT, inspecting default settings, updating settings, creating an idempotent todo, fetching, updating, listing, and deleting it.

```mermaid
sequenceDiagram
    autonumber
    actor Client as Client / Runner
    participant API as Todo API
    Client->>API: registerUser: Register a new end-user account
    API-->>Client: 201 (outputs: userId, userName)
    Client->>API: loginUser: Authenticate user credentials and acquire JWT token
    API-->>Client: 200 (outputs: jwtToken)
    Client->>API: getUserSettings: Fetch initial default user settings
    API-->>Client: 200
    Client->>API: updateUserSettings: Customize user settings for default priority and duration
    API-->>Client: 200
    Client->>API: createTodo: Create a new Todo item with an idempotency key
    API-->>Client: 201 (outputs: createdTodoId)
    Client->>API: getTodo: Fetch the created Todo item by ID
    API-->>Client: 200
    Client->>API: updateTodo: Mark the Todo item as completed
    API-->>Client: 200
    Client->>API: listTodos: List all Todos for the authenticated user
    API-->>Client: 200
    Client->>API: patchTodo: Partially update the Todo priority
    API-->>Client: 200
    Client->>API: deleteTodo: Delete the completed Todo item
    API-->>Client: 204
```

---

### ワークフロー: `admin-audit-lifecycle`
**Administrator system audit**

Inspect system statistics and registered users using administrator API key.

```mermaid
sequenceDiagram
    autonumber
    actor Client as Client / Runner
    participant API as Todo API
    Client->>API: getAdminStats: Fetch system-wide usage statistics
    API-->>Client: 200
    Client->>API: getAdminUsers: Fetch registered users directory
    API-->>Client: 200
```

---

### ワークフロー: `project-collaboration-lifecycle`
**Shared project creation, member management, and project-scoped Todo lifecycle**

Demonstrates creating a shared project, inviting a collaborator, creating and listing project-scoped Todos, removing a member, and deleting the project.

```mermaid
sequenceDiagram
    autonumber
    actor Client as Client / Runner
    participant API as Todo API
    Client->>API: registerProjectOwner: Register a project owner account
    API-->>Client: 201 (outputs: ownerId, ownerName)
    Client->>API: registerProjectMember: Register a project collaborator account
    API-->>Client: 201 (outputs: memberId, memberName)
    Client->>API: loginProjectOwner: Authenticate project owner and acquire JWT token
    API-->>Client: 200 (outputs: ownerToken)
    Client->>API: createProject: Create a shared project
    API-->>Client: 201 (outputs: projectId)
    Client->>API: listProjects: List projects accessible to the owner
    API-->>Client: 200
    Client->>API: getProject: Fetch project details by ID
    API-->>Client: 200
    Client->>API: updateProject: Update project name and description
    API-->>Client: 200
    Client->>API: addProjectMember: Add collaborator to the shared project
    API-->>Client: 201
    Client->>API: listProjectMembers: List members of the shared project
    API-->>Client: 200
    Client->>API: createProjectTodo: Create a Todo scoped to the shared project
    API-->>Client: 201
    Client->>API: listProjectTodos: List Todos filtered by projectId
    API-->>Client: 200
    Client->>API: removeProjectMember: Remove collaborator from the shared project
    API-->>Client: 204
    Client->>API: deleteProject: Delete the shared project
    API-->>Client: 204
```

---
