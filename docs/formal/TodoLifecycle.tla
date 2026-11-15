---- MODULE TodoLifecycle ----
EXTENDS Integers, FiniteSets, Sequences

(*
  Formal behavioral specification supplementing docs/openapi.yaml for:
    - POST   /api/v1/todos      (createTodo with optional Idempotency-Key)
    - PATCH  /api/v1/todos/{id} (patchTodo: toggle completed)
    - DELETE /api/v1/todos/{id} (deleteTodo)

  Models the current application semantics in:
    - TodoService.kt (active todo limit check, save, Pub/Sub event publish)
    - TodoIdempotencyHandler.kt (24h retention, fingerprint check, record save)

  This specification deliberately models the non-atomic gap between
  "check limit / idempotency" and "persist todo + idempotency record"
  to surface concurrency edge cases under multiple workers/instances.
*)

CONSTANTS
    Keys,            \* Set of Idempotency-Key values, e.g. {"k1", "k2"}
    Fingerprints,    \* Set of request payload fingerprints, e.g. {"fp1", "fp2"}
    MaxActiveTodos,  \* Per-user active (completed = FALSE) Todo limit
    RetentionTicks,  \* Idempotency record TTL in logical clock ticks
    MaxTime,         \* Upper bound for bounded model checking of logical clock
    Workers          \* Concurrent request handlers / app instances

VARIABLES
    todos,               \* Set of Todo records currently persisted
    idempotencyRecords,  \* Function: Keys -> IdempotencyRecord
    publishedEvents,     \* Sequence of published TodoEvent records
    nextTodoId,          \* Monotonic Todo ID generator
    now,                 \* Logical clock (0..MaxTime)
    workerState          \* Per-worker execution state for multi-step requests

vars == <<todos, idempotencyRecords, publishedEvents, nextTodoId, now, workerState>>

NullIdempotencyRecord ==
    [present |-> FALSE, fingerprint |-> "NONE", todoId |-> 0, expiresAt |-> 0]

IdleWorker ==
    [phase |-> "IDLE", key |-> "NONE", fp |-> "NONE", completed |-> FALSE, todoId |-> 0]

ActiveTodos == {t \in todos : ~t.completed}
ActiveTodoCount == Cardinality(ActiveTodos)

HasValidRecord(k) ==
    idempotencyRecords[k].present /\ idempotencyRecords[k].expiresAt > now

\* ---------------------------------------------------------------------------
\* Initial State
\* ---------------------------------------------------------------------------

Init ==
    /\ todos = {}
    /\ idempotencyRecords = [k \in Keys |-> NullIdempotencyRecord]
    /\ publishedEvents = <<>>
    /\ nextTodoId = 1
    /\ now = 0
    /\ workerState = [w \in Workers |-> IdleWorker]

\* ---------------------------------------------------------------------------
\* Actions mapped to OpenAPI operations
\* ---------------------------------------------------------------------------

\* Step 1 of POST /api/v1/todos (with Idempotency-Key):
\* Check existing idempotency record and active todo limit.
StartCreateIdempotent(w, k, fp, completed) ==
    /\ workerState[w].phase = "IDLE"
    /\ IF HasValidRecord(k) THEN
           \* Either 200/201 replay (same fingerprint) or 409 Conflict (different fingerprint)
           \* No state mutation occurs.
           UNCHANGED vars
       ELSE
           \* Passed idempotency check; check active limit if creating an active Todo
           /\ (completed \/ ActiveTodoCount < MaxActiveTodos)
           /\ workerState' = [workerState EXCEPT ![w] =
                  [phase |-> "CREATING", key |-> k, fp |-> fp, completed |-> completed, todoId |-> 0]]
           /\ UNCHANGED <<todos, idempotencyRecords, publishedEvents, nextTodoId, now>>

\* Step 2 of POST /api/v1/todos:
\* Persist Todo and publish CREATED event (mirrors TodoService.create).
PersistTodoAndEvent(w) ==
    /\ workerState[w].phase = "CREATING"
    /\ LET st == workerState[w]
           newTodo == [id |-> nextTodoId, completed |-> st.completed, key |-> st.key]
           newEvent == [type |-> "CREATED", todoId |-> nextTodoId]
       IN
       /\ todos' = todos \cup {newTodo}
       /\ publishedEvents' = Append(publishedEvents, newEvent)
       /\ nextTodoId' = nextTodoId + 1
       /\ workerState' = [workerState EXCEPT ![w].phase = "SAVING_IDEMPOTENCY",
                                             ![w].todoId = nextTodoId]
       /\ UNCHANGED <<idempotencyRecords, now>>

\* Step 3 of POST /api/v1/todos:
\* Persist IdempotencyRecord (mirrors TodoIdempotencyHandler.execute after onCreate).
SaveIdempotencyRecord(w) ==
    /\ workerState[w].phase = "SAVING_IDEMPOTENCY"
    /\ LET st == workerState[w]
       IN
       /\ idempotencyRecords' = [idempotencyRecords EXCEPT ![st.key] =
              [present |-> TRUE,
               fingerprint |-> st.fp,
               todoId |-> st.todoId,
               expiresAt |-> now + RetentionTicks]]
       /\ workerState' = [workerState EXCEPT ![w] = IdleWorker]
       /\ UNCHANGED <<todos, publishedEvents, nextTodoId, now>>

\* PATCH /api/v1/todos/{id}: Toggle completed status (respecting active limit on reopen).
PatchToggleCompleted(t) ==
    /\ t \in todos
    /\ LET newCompleted == ~t.completed
       IN
       /\ (~t.completed \/ ActiveTodoCount < MaxActiveTodos)
       /\ todos' = (todos \ {t}) \cup {[t EXCEPT !.completed = newCompleted]}
       /\ publishedEvents' = Append(publishedEvents, [type |-> "UPDATED", todoId |-> t.id])
       /\ UNCHANGED <<idempotencyRecords, nextTodoId, now, workerState>>

\* DELETE /api/v1/todos/{id}
DeleteTodo(t) ==
    /\ t \in todos
    /\ todos' = todos \ {t}
    /\ publishedEvents' = Append(publishedEvents, [type |-> "DELETED", todoId |-> t.id])
    /\ UNCHANGED <<idempotencyRecords, nextTodoId, now, workerState>>

\* Logical clock advance for TTL expiration
AdvanceClock ==
    /\ now < MaxTime
    /\ now' = now + 1
    /\ UNCHANGED <<todos, idempotencyRecords, publishedEvents, nextTodoId, workerState>>

Next ==
    \/ \E w \in Workers, k \in Keys, fp \in Fingerprints, c \in BOOLEAN :
           StartCreateIdempotent(w, k, fp, c)
    \/ \E w \in Workers : PersistTodoAndEvent(w)
    \/ \E w \in Workers : SaveIdempotencyRecord(w)
    \/ \E t \in todos : PatchToggleCompleted(t)
    \/ \E t \in todos : DeleteTodo(t)
    \/ AdvanceClock

Spec == Init /\ [][Next]_vars

\* ---------------------------------------------------------------------------
\* Invariants (Contract Properties supplementing OpenAPI)
\* ---------------------------------------------------------------------------

TypeOK ==
    /\ now \in 0..MaxTime
    /\ nextTodoId \in 1..(MaxTime * 10 + 100)
    /\ \A k \in Keys : idempotencyRecords[k].present \in BOOLEAN

\* Property 1: Active Todo count must never exceed MaxActiveTodos.
\* (Expected to be VIOLATED under concurrent StartCreateIdempotent without DB-level serialization)
ActiveTodosLimitInvariant ==
    ActiveTodoCount <= MaxActiveTodos

\* Property 2: No two CREATED events are ever emitted for the same Todo ID.
NoDuplicateCreatedEvents ==
    \A i, j \in 1..Len(publishedEvents) :
        (i # j /\ publishedEvents[i].type = "CREATED" /\ publishedEvents[j].type = "CREATED")
        => publishedEvents[i].todoId # publishedEvents[j].todoId

\* Property 3: Every persisted Todo has a corresponding CREATED event.
CreatedTodoHasEvent ==
    \A t \in todos :
        \E i \in 1..Len(publishedEvents) :
            publishedEvents[i].type = "CREATED" /\ publishedEvents[i].todoId = t.id

====
