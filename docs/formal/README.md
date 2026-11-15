# Formal Specifications (TLA+)

[docs/openapi.yaml](../openapi.yaml)（単発 HTTP I/O の型）や [docs/workflows/todo-workflows.arazzo.yaml](../workflows/todo-workflows.arazzo.yaml)（代表シナリオ）では表現できない、**状態遷移・不変条件・並行実行時の振る舞い**を補う TLA+ 仕様です。

## ファイル一覧

| ファイル | 対象 | 検証する性質 |
| :--- | :--- | :--- |
| [TodoLifecycle.tla](TodoLifecycle.tla) | `POST /api/v1/todos`（冪等性含む）, `PATCH /api/v1/todos/{id}`, `DELETE /api/v1/todos/{id}` | `ActiveTodosLimitInvariant`（上限超過しないか）、`NoDuplicateCreatedEvents`、`CreatedTodoHasEvent` |
| [TodoLifecycle.cfg](TodoLifecycle.cfg) | TLC モデル検査用の定数・不変条件設定 | 2 ワーカー並行実行・上限 2・TTL 2 ティックでの網羅探索 |

## ローカル実行方法（TLC モデル検査器）

Java 21 が入っていれば、`tla2tools.jar` を取得してそのまま実行できます。

```bash
curl -fsSL -o /tmp/tla2tools.jar \
  https://github.com/tlaplus/tlaplus/releases/download/v1.8.0/tla2tools.jar

java -cp /tmp/tla2tools.jar tlc2.TLC \
  -config docs/formal/TodoLifecycle.cfg \
  docs/formal/TodoLifecycle.tla
```

### 期待される検出例
現在の [TodoLifecycle.tla](TodoLifecycle.tla) は、`Workers = {"w1", "w2"}` が同時に「上限チェック通過 (`StartCreateIdempotent`) → 保存 (`PersistTodoAndEvent`)」を行う非原子的な挙動をモデル化しているため、TLC を実行すると `ActiveTodosLimitInvariant` 違反の反例トレース（2 ワーカーが同時に `ActiveTodoCount < MaxActiveTodos` を通過して 3 件目を作ってしまうケース）が検出されます。
