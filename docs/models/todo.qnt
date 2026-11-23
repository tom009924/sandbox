// Quint (TLA+-compatible) formal model for the Todo API.
// Action names and `lastAction` values map 1:1 to OpenAPI `operationId`s in docs/openapi.yaml.
// Verify invariants and generate an Informal Trace Format (ITF) trace with:
//   quint run docs/models/todo.qnt --invariant=activeTodosWithinLimit --out-itf=docs/models/traces/todo-model-trace.itf.json
module todo_model {
  type TodoState = {
    completed: bool,
    subtasks: Set[str],
  }

  type IdempotencyEntry = {
    todoId: str,
    fingerprint: str,
  }

  pure val TODO_IDS: Set[str] = Set("todo-1", "todo-2", "todo-3")
  pure val SUBTASK_IDS: Set[str] = Set("sub-1", "sub-2")
  pure val IDEMPOTENCY_KEYS: Set[str] = Set("key-1", "key-2", "key-3")
  pure val FINGERPRINTS: Set[str] = Set("fp-a", "fp-b")

  var maxActiveTodos: int
  var todos: str -> TodoState
  var idempotencyKeys: str -> IdempotencyEntry

  // Observable transition metadata exported to ITF traces for API replay
  var lastAction: str
  var lastStatus: int
  var lastTargetId: str
  var lastSubtaskId: str
  var lastKey: str
  var lastFingerprint: str
  var lastCompleted: bool

  pure def activeCount(t: str -> TodoState): int =
    t.keys().filter(id => not(t.get(id).completed)).size()

  action init = all {
    maxActiveTodos' = 2,
    todos' = Map(),
    idempotencyKeys' = Map(),
    lastAction' = "init",
    lastStatus' = 0,
    lastTargetId' = "",
    lastSubtaskId' = "",
    lastKey' = "",
    lastFingerprint' = "",
    lastCompleted' = false,
  }

  // OpenAPI operationId: createTodo (201 new, 201 idempotent replay, 409 conflict, 400 limit exceeded)
  action createTodo(key: str, fp: str, newId: str): bool = {
    if (key.in(idempotencyKeys.keys())) {
      val existing = idempotencyKeys.get(key)
      if (existing.fingerprint == fp) all {
        // Idempotent replay returns 201 with original todoId
        maxActiveTodos' = maxActiveTodos,
        todos' = todos,
        idempotencyKeys' = idempotencyKeys,
        lastAction' = "createTodo",
        lastStatus' = 201,
        lastTargetId' = existing.todoId,
        lastSubtaskId' = "",
        lastKey' = key,
        lastFingerprint' = fp,
        lastCompleted' = false,
      } else all {
        // Reusing key with different payload returns 409 Conflict
        maxActiveTodos' = maxActiveTodos,
        todos' = todos,
        idempotencyKeys' = idempotencyKeys,
        lastAction' = "createTodo",
        lastStatus' = 409,
        lastTargetId' = existing.todoId,
        lastSubtaskId' = "",
        lastKey' = key,
        lastFingerprint' = fp,
        lastCompleted' = false,
      }
    } else if (activeCount(todos) >= maxActiveTodos) all {
      // Exceeding maxActiveTodos returns 400 Bad Request
      maxActiveTodos' = maxActiveTodos,
      todos' = todos,
      idempotencyKeys' = idempotencyKeys,
      lastAction' = "createTodo",
      lastStatus' = 400,
      lastTargetId' = "",
      lastSubtaskId' = "",
      lastKey' = key,
      lastFingerprint' = fp,
      lastCompleted' = false,
    } else all {
      not(newId.in(todos.keys())),
      maxActiveTodos' = maxActiveTodos,
      todos' = todos.put(newId, { completed: false, subtasks: Set() }),
      idempotencyKeys' = idempotencyKeys.put(key, { todoId: newId, fingerprint: fp }),
      lastAction' = "createTodo",
      lastStatus' = 201,
      lastTargetId' = newId,
      lastSubtaskId' = "",
      lastKey' = key,
      lastFingerprint' = fp,
      lastCompleted' = false,
    }
  }

  // OpenAPI operationId: getTodo (200 / 404)
  action getTodo(id: str): bool = all {
    maxActiveTodos' = maxActiveTodos,
    todos' = todos,
    idempotencyKeys' = idempotencyKeys,
    lastAction' = "getTodo",
    lastStatus' = if (id.in(todos.keys())) 200 else 404,
    lastTargetId' = id,
    lastSubtaskId' = "",
    lastKey' = "",
    lastFingerprint' = "",
    lastCompleted' = if (id.in(todos.keys())) todos.get(id).completed else false,
  }

  // OpenAPI operationId: listTodos (200)
  action listTodos: bool = all {
    maxActiveTodos' = maxActiveTodos,
    todos' = todos,
    idempotencyKeys' = idempotencyKeys,
    lastAction' = "listTodos",
    lastStatus' = 200,
    lastTargetId' = "",
    lastSubtaskId' = "",
    lastKey' = "",
    lastFingerprint' = "",
    lastCompleted' = false,
  }

  // OpenAPI operationId: updateTodo (200 / 400 reopen limit exceeded / 404)
  action updateTodo(id: str, completed: bool): bool = {
    if (not(id.in(todos.keys()))) all {
      maxActiveTodos' = maxActiveTodos,
      todos' = todos,
      idempotencyKeys' = idempotencyKeys,
      lastAction' = "updateTodo",
      lastStatus' = 404,
      lastTargetId' = id,
      lastSubtaskId' = "",
      lastKey' = "",
      lastFingerprint' = "",
      lastCompleted' = completed,
    } else {
      val current = todos.get(id)
      val reopening = current.completed and not(completed)
      if (reopening and activeCount(todos) >= maxActiveTodos) all {
        maxActiveTodos' = maxActiveTodos,
        todos' = todos,
        idempotencyKeys' = idempotencyKeys,
        lastAction' = "updateTodo",
        lastStatus' = 400,
        lastTargetId' = id,
        lastSubtaskId' = "",
        lastKey' = "",
        lastFingerprint' = "",
        lastCompleted' = completed,
      } else all {
        maxActiveTodos' = maxActiveTodos,
        todos' = todos.put(id, { ...current, completed: completed }),
        idempotencyKeys' = idempotencyKeys,
        lastAction' = "updateTodo",
        lastStatus' = 200,
        lastTargetId' = id,
        lastSubtaskId' = "",
        lastKey' = "",
        lastFingerprint' = "",
        lastCompleted' = completed,
      }
    }
  }

  // OpenAPI operationId: patchTodo (200 / 400 reopen limit exceeded / 404)
  action patchTodo(id: str, completed: bool): bool = {
    if (not(id.in(todos.keys()))) all {
      maxActiveTodos' = maxActiveTodos,
      todos' = todos,
      idempotencyKeys' = idempotencyKeys,
      lastAction' = "patchTodo",
      lastStatus' = 404,
      lastTargetId' = id,
      lastSubtaskId' = "",
      lastKey' = "",
      lastFingerprint' = "",
      lastCompleted' = completed,
    } else {
      val current = todos.get(id)
      val reopening = current.completed and not(completed)
      if (reopening and activeCount(todos) >= maxActiveTodos) all {
        maxActiveTodos' = maxActiveTodos,
        todos' = todos,
        idempotencyKeys' = idempotencyKeys,
        lastAction' = "patchTodo",
        lastStatus' = 400,
        lastTargetId' = id,
        lastSubtaskId' = "",
        lastKey' = "",
        lastFingerprint' = "",
        lastCompleted' = completed,
      } else all {
        maxActiveTodos' = maxActiveTodos,
        todos' = todos.put(id, { ...current, completed: completed }),
        idempotencyKeys' = idempotencyKeys,
        lastAction' = "patchTodo",
        lastStatus' = 200,
        lastTargetId' = id,
        lastSubtaskId' = "",
        lastKey' = "",
        lastFingerprint' = "",
        lastCompleted' = completed,
      }
    }
  }

  // OpenAPI operationId: deleteTodo (204 / 404)
  action deleteTodo(id: str): bool = {
    if (not(id.in(todos.keys()))) all {
      maxActiveTodos' = maxActiveTodos,
      todos' = todos,
      idempotencyKeys' = idempotencyKeys,
      lastAction' = "deleteTodo",
      lastStatus' = 404,
      lastTargetId' = id,
      lastSubtaskId' = "",
      lastKey' = "",
      lastFingerprint' = "",
      lastCompleted' = false,
    } else all {
      maxActiveTodos' = maxActiveTodos,
      todos' = todos.keys().exclude(Set(id)).mapBy(k => todos.get(k)),
      idempotencyKeys' = idempotencyKeys,
      lastAction' = "deleteTodo",
      lastStatus' = 204,
      lastTargetId' = id,
      lastSubtaskId' = "",
      lastKey' = "",
      lastFingerprint' = "",
      lastCompleted' = false,
    }
  }

  // OpenAPI operationId: createTodoSubtask (201 / 404)
  action createTodoSubtask(todoId: str, subId: str): bool = {
    if (not(todoId.in(todos.keys()))) all {
      maxActiveTodos' = maxActiveTodos,
      todos' = todos,
      idempotencyKeys' = idempotencyKeys,
      lastAction' = "createTodoSubtask",
      lastStatus' = 404,
      lastTargetId' = todoId,
      lastSubtaskId' = subId,
      lastKey' = "",
      lastFingerprint' = "",
      lastCompleted' = false,
    } else {
      val current = todos.get(todoId)
      all {
        not(subId.in(current.subtasks)),
        maxActiveTodos' = maxActiveTodos,
        todos' = todos.put(todoId, { ...current, subtasks: current.subtasks.union(Set(subId)) }),
        idempotencyKeys' = idempotencyKeys,
        lastAction' = "createTodoSubtask",
        lastStatus' = 201,
        lastTargetId' = todoId,
        lastSubtaskId' = subId,
        lastKey' = "",
        lastFingerprint' = "",
        lastCompleted' = current.completed,
      }
    }
  }

  // OpenAPI operationId: listTodoSubtasks (200 / 404)
  action listTodoSubtasks(todoId: str): bool = all {
    maxActiveTodos' = maxActiveTodos,
    todos' = todos,
    idempotencyKeys' = idempotencyKeys,
    lastAction' = "listTodoSubtasks",
    lastStatus' = if (todoId.in(todos.keys())) 200 else 404,
    lastTargetId' = todoId,
    lastSubtaskId' = "",
    lastKey' = "",
    lastFingerprint' = "",
    lastCompleted' = false,
  }

  // OpenAPI operationId: deleteTodoSubtask (204 / 404)
  action deleteTodoSubtask(todoId: str, subId: str): bool = {
    if (not(todoId.in(todos.keys())) or not(subId.in(todos.get(todoId).subtasks))) all {
      maxActiveTodos' = maxActiveTodos,
      todos' = todos,
      idempotencyKeys' = idempotencyKeys,
      lastAction' = "deleteTodoSubtask",
      lastStatus' = 404,
      lastTargetId' = todoId,
      lastSubtaskId' = subId,
      lastKey' = "",
      lastFingerprint' = "",
      lastCompleted' = false,
    } else {
      val current = todos.get(todoId)
      all {
        maxActiveTodos' = maxActiveTodos,
        todos' = todos.put(todoId, { ...current, subtasks: current.subtasks.exclude(Set(subId)) }),
        idempotencyKeys' = idempotencyKeys,
        lastAction' = "deleteTodoSubtask",
        lastStatus' = 204,
        lastTargetId' = todoId,
        lastSubtaskId' = subId,
        lastKey' = "",
        lastFingerprint' = "",
        lastCompleted' = current.completed,
      }
    }
  }

  // OpenAPI operationId: updateUserSettings (200)
  action updateUserSettings(newLimit: int): bool = all {
    newLimit >= activeCount(todos),
    newLimit >= 1,
    newLimit <= 3,
    maxActiveTodos' = newLimit,
    todos' = todos,
    idempotencyKeys' = idempotencyKeys,
    lastAction' = "updateUserSettings",
    lastStatus' = 200,
    lastTargetId' = "",
    lastSubtaskId' = "",
    lastKey' = "",
    lastFingerprint' = "",
    lastCompleted' = false,
  }

  action step = any {
    nondet key = oneOf(IDEMPOTENCY_KEYS)
    nondet fp = oneOf(FINGERPRINTS)
    nondet id = oneOf(TODO_IDS)
    createTodo(key, fp, id),

    nondet id = oneOf(TODO_IDS)
    getTodo(id),

    listTodos,

    nondet id = oneOf(TODO_IDS)
    nondet completed = oneOf(Set(true, false))
    updateTodo(id, completed),

    nondet id = oneOf(TODO_IDS)
    nondet completed = oneOf(Set(true, false))
    patchTodo(id, completed),

    nondet id = oneOf(TODO_IDS)
    deleteTodo(id),

    nondet id = oneOf(TODO_IDS)
    nondet subId = oneOf(SUBTASK_IDS)
    createTodoSubtask(id, subId),

    nondet id = oneOf(TODO_IDS)
    listTodoSubtasks(id),

    nondet id = oneOf(TODO_IDS)
    nondet subId = oneOf(SUBTASK_IDS)
    deleteTodoSubtask(id, subId),

    nondet newLimit = oneOf(Set(1, 2, 3))
    updateUserSettings(newLimit),
  }

  // Invariants checked by Quint before any API test runs
  val activeTodosWithinLimit = activeCount(todos) <= maxActiveTodos
  val nonNegativeLimit = maxActiveTodos >= 1
}
