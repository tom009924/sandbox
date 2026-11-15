---- MODULE TodoLifecycleTrace ----
EXTENDS TodoLifecycle

(*
  Trace Validation module for verifying that observed integration-test traces
  conform to the formal TodoLifecycle specification.

  Recorded traces from BehavioralCoverageTracker can be injected into TraceLog
  (each entry: [op |-> "createTodo" | "patchTodo" | "deleteTodo" | "tick", ...])
  and checked with TLC: if any observed step cannot be matched by TodoLifecycle!Next,
  TLC fails immediately with a trace mismatch.
*)

CONSTANT TraceLog

VARIABLE traceIdx

traceVars == <<vars, traceIdx>>

TraceInit ==
    /\ Init
    /\ traceIdx = 1

StepMatches(entry) ==
    \/ /\ entry.op = "createTodo"
       /\ StartCreateIdempotent(entry.worker, entry.key, entry.fp, entry.completed)
    \/ /\ entry.op = "persistTodo"
       /\ PersistTodoAndEvent(entry.worker)
    \/ /\ entry.op = "saveIdempotency"
       /\ SaveIdempotencyRecord(entry.worker)
    \/ /\ entry.op \in {"updateTodo", "patchTodo"}
       /\ \E t \in todos : t.id = entry.todoId /\ PatchToggleCompleted(t)
    \/ /\ entry.op = "deleteTodo"
       /\ \E t \in todos : t.id = entry.todoId /\ DeleteTodo(t)
    \/ /\ entry.op = "tick"
       /\ AdvanceClock

TraceNext ==
    /\ traceIdx <= Len(TraceLog)
    /\ StepMatches(TraceLog[traceIdx])
    /\ traceIdx' = traceIdx + 1

TraceSpec == TraceInit /\ [][TraceNext]_traceVars

\* If TraceAccepted is checked as a temporal property or via deadlock-free completion,
\* traceIdx must reach Len(TraceLog) + 1.
TraceCompleted == traceIdx = Len(TraceLog) + 1

====
