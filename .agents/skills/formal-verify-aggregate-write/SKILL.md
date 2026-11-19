---
name: formal-verify-aggregate-write
description: 集約の保存を、PostgreSQL の READ COMMITTED と行ロックのモデルで、二つの保存の全順序について TLA+ で検査する。TableWriter、LockedRoot、Repository の update、子のテーブルの一意制約、分離レベル、docs/database/postgresql-concurrency-control.md の保存の順序を変えるときと、更新の消失、集約の混ざり、デッドロック、422 や 500 の競合を疑うときに使う。
---

# 集約の保存の形式検証

同じ集約（親の行と子の行）を二つの要求が同時に保存するときの、すべての順序を検査する。
道具は TLA+ の TLC である。
問いが「二つのトランザクションの文がどう交互に並んでも、楽観的ロックと行ロックで保存が正しく直列になるか」であり、テストでは文の間の割り込みとロック待ちの解け方を網羅できないためである。
検査の実行、結果の読み方、切り分け、モデルの直し方、同期コミットの進め方は `docs/agents/formal-verification.md` に従う。

モデルにした保存の経路は、今の唯一の呼び出し元である `JooqOrderRepository.update` である。
`docs/database/postgresql-concurrency-control.md` の「親子のテーブルの保存の順序」に従う Repository は同じ形になるため、新しい集約もこの検査の範囲に入る。

## 実行する

```bash
tooling/formal-verify/check.sh .agents/skills/formal-verify-aggregate-write
tooling/formal-verify/mutants.py .agents/skills/formal-verify-aggregate-write
```

## 約束（不変条件）

| 名前                 | 約束                                                                                                     | 破れたときの害                                 |
| -------------------- | -------------------------------------------------------------------------------------------------------- | ---------------------------------------------- |
| `NoLostUpdate`       | コミットした二つの保存が、同じ版（`lock_no`）から始まらない。                                            | 先の保存の変更が、後の保存で黙って消える。     |
| `AggregateWhole`     | コミットした集約の子の行は、最後にコミットした一つの保存の子の行と一致する。                             | 二つの保存の明細が混ざった注文が残る。         |
| `OnlyConflictErrors` | 競合に負けた保存は、再読み込みを促す 409（`ConflictException` の `VERSION` か `LOCK`）だけで失敗する。 | 利用者に 422 や 500 が返り、原因が分からない。 |

## 設定と期待する結果

| 設定            | 内容                                                                     | 期待する結果 |
| --------------- | ------------------------------------------------------------------------ | ------------ |
| `two-saves.cfg` | 二つの要求が、最初の明細と保存する明細のどの組み合わせでも同時に保存する | pass         |

## 切り分けの手がかり

- 各ステップが一つの SQL の文か、その文が PostgreSQL でその結果になるかを確かめる。
  モデルの冒頭の注記に、モデルが頼る PostgreSQL の振る舞いを並べてある。
- 親の行のロックが二つの要求の子の行の文を順に並べるため、今のコードでは子の行の文がロックを待たない。
  親の行を先に書かない変更では、モデルは PostgreSQL より粗くなる（冒頭の注記）。
  その経路の違反は、結合テストで二つのトランザクションをその順序で動かして確かめる。

## 変更がこの検証に関わるか

次のコマンドが差分を出せば関わる。

```bash
git diff --stat a99d855a9e7830988e3bd312609c65cb20fe38cd -- \
  backend/src/main/java/com/example/demo/shared/infrastructure/persistence/TableWriter.java \
  backend/src/main/java/com/example/demo/shared/infrastructure/persistence/LockedRoot.java \
  backend/src/main/java/com/example/demo/ordering/infrastructure/persistence/JooqOrderRepository.java \
  backend/src/main/java/com/example/demo/ordering/domain/model/Order.java \
  backend/src/main/java/com/example/demo/ordering/application/ChangeOrderLinesCommandHandler.java \
  backend/src/main/java/com/example/demo/ordering/application/ConfirmOrderCommandHandler.java \
  backend/src/main/java/com/example/demo/ordering/application/CancelOrderCommandHandler.java \
  backend/src/main/java/com/example/demo/error/presentation/web/ApiExceptionHandler.java \
  backend/src/main/resources/db/changelog/changesets/004-create-order-tables.yaml \
  backend/src/main/resources/application.yaml \
  docs/database/postgresql-concurrency-control.md
```

`application.yaml` は `connection-init-sql`（`lock_timeout` など）だけが関わる。
`ApiExceptionHandler` は、例外から HTTP のステータスへの対応だけが関わる。
`updateCheckingVersion` か `deleteCheckingVersion` の新しい呼び出し元（ほかの集約の Repository）ができたら、その保存の順序が `JooqOrderRepository.update` と同じかを確かめる。
違うなら、その経路をモデルに足す。

```bash
grep -rn "updateCheckingVersion\|deleteCheckingVersion" backend/src/main/java --include=*.java
```

## 対応表

モデルは `model/AggregateWrite.tla` にある。
各アクションは一つの SQL の文か、トランザクションの終わりである。

| アクション          | コード                                                                                                                                                                                              |
| ------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ScreenRead`        | 画面が注文を読んで `lock_no` を持つ（前の要求）。それを `expectedLockNo` として送り返す                                                                                                            |
| `Find`              | CommandHandler の `JooqOrderRepository.findById`（multiset の一つの SELECT）と `Order.ensureLockNo`                                                                                                 |
| `UpdateRoot`        | `TableWriter.updateCheckingVersion`（`lock_no = expected` の UPDATE。0 件なら `requireOneRow` が `ConflictException`）                                                                            |
| `ReadSaved`         | `JooqOrderRepository.update` の保存済みの `line_no` の SELECT と、それに続く文の順序の決定                                                                                                         |
| `ChildOp`           | `LockedRoot.deleteChildren`（`line_no NOT IN (集約の明細)` の一つの DELETE）、`LockedRoot.updateChild`（0 件は `BusinessRuleViolationException`）、`insertLine(...).execute()`（`uk_t_order_line` の違反は変換されない） |
| `OpsDone`、`Commit` | `@Transactional` のコミット                                                                                                                                                                         |
| `LockTimeout`       | `lock_timeout`（55P03）。`TableWriter` を通る文は `ConflictException`（`LOCK`）の 409、`insertLine` は `CannotAcquireLockException` のまま `ApiExceptionHandler.handleException` の 500           |
| `Deadlock`          | PostgreSQL のデッドロックの検出（40P01）。どこも変換しない                                                                                                                                          |

## 変異

変異は `model/mutants.toml` にあり、各項目の `change` が写すコードの退行である。

## 範囲と前提

検査の範囲は次のとおりで、これを超える保証はない。

- 集約は一つ、保存する要求は二つ、子の行の鍵は 2 個（`line_no` の 1 と 2）。
- 保存は `update` だけを扱う。
  `deleteCheckingVersion` と `DeletedRoot` は今は呼び出し元がないため扱わない。
- 集約の INSERT（`add`）、版を持たない書き込み（`updateWhere`、`deleteWhere`）と、ルートを書かずに子の行を書く処理（ADR-054 の Negative）は扱わない。
- 読み取りは一つの文（multiset）なので、文の時点のコミット済みの状態を見る。
  読み取りが二つの文に分かれる変更は扱わない。
- 分離レベルは READ COMMITTED だけを扱う。
- `ModuleRoleTransactionManager` のトランザクションごとのロールの切り替えは、ロックと文の順序を変えないため扱わない。

## 既知の発見

なし。

## 同期の記録

同期コミット：`a99d855a9e7830988e3bd312609c65cb20fe38cd`（main）

このコミットで、「変更がこの検証に関わるか」のファイルと対応表がモデルと一致することを確かめた。
読んだのはこのリポジトリのコードで、PostgreSQL の振る舞いはモデルの冒頭の注記に並べた前提である。
