---
name: formal-verify-aggregate-write
description: 集約の保存を、PostgreSQL の READ COMMITTED と行ロックのモデルで、二つの保存の全順序について TLA+ で検査する。TableWriter、ChildWriter、Repository の update、子のテーブルの一意制約、分離レベル、docs/database/postgresql-concurrency-control.md の保存の順序を変えるときと、更新の消失、集約の混ざり、デッドロック、422 や 500 の競合を疑うときに使う。
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

| 名前                 | 約束                                                                                                                                                                                | 破れたときの害                                 |
| -------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------- |
| `NoLostUpdate`       | コミットした二つの保存が、同じ版（`lock_no`）から始まらない。                                                                                                                       | 先の保存の変更が、後の保存で黙って消える。     |
| `AggregateWhole`     | コミットした集約の子の行は、最後にコミットした一つの保存の子の行と一致する。                                                                                                        | 二つの保存の明細が混ざった注文が残る。         |
| `OnlyConflictErrors` | 競合に負けた保存は、再読み込みを促す 409（`ConflictException` の `VERSION` か `LOCK`）だけで失敗する。子の `UNIQUE` の 409 も、ルートの行ロックが防ぐため到達しないことを確かめる。 | 利用者に 422 や 500 が返り、原因が分からない。 |

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
skill=.agents/skills/formal-verify-aggregate-write/SKILL.md
git diff --stat "$(sed -n 's/^同期コミット：`\([0-9a-f]*\)`.*/\1/p' "$skill")" -- \
  backend/src/main/java/com/example/demo/shared/persistence/TableWriter.java \
  backend/src/main/java/com/example/demo/shared/persistence/AggregateTable.java \
  backend/src/main/java/com/example/demo/shared/persistence/ChildTable.java \
  backend/src/main/java/com/example/demo/shared/persistence/ChildRows.java \
  backend/src/main/java/com/example/demo/shared/persistence/ChildWriter.java \
  backend/src/main/java/com/example/demo/shared/persistence/ConflictTranslator.java \
  backend/src/main/java/com/example/demo/ordering/infrastructure/persistence/JooqOrderRepository.java \
  backend/src/main/java/com/example/demo/ordering/domain/model/Order.java \
  backend/src/main/java/com/example/demo/ordering/application/ChangeOrderLinesCommandHandler.java \
  backend/src/main/java/com/example/demo/ordering/application/ConfirmOrderCommandHandler.java \
  backend/src/main/java/com/example/demo/ordering/application/CancelOrderCommandHandler.java \
  backend/src/main/java/com/example/demo/error/presentation/web/ApiExceptionHandler.java \
  backend/src/main/java/com/example/demo/shared/persistence/ModuleRoleTransactionManager.java \
  backend/src/main/java/com/example/demo/shared/persistence/ModuleRoles.java \
  backend/src/main/resources/db/changelog/changesets/004-create-order-tables.yaml \
  backend/src/main/resources/db/changelog/changesets/019-rebuild-order-tables-for-tenants.yaml \
  backend/src/main/resources/application.yaml \
  docs/database/postgresql-concurrency-control.md
```

`application.yaml` は `connection-init-sql`（`lock_timeout` など）だけが関わる。
`ApiExceptionHandler` は、例外から HTTP のステータスへの対応だけが関わる。
`ModuleRoleTransactionManager` は、開始直後の `set_config` の一文と、開始前の拒否だけが関わる。
`ModuleRoles` は、どのトランザクションがテナントを要するか（その拒否の対象）だけが関わる。
両方とも、範囲と前提の `ModuleRoleTransactionManager` の項目に結びつく。
`TableWriter.update` の新しい呼び出し元（ほかの集約の Repository）ができたら、その保存の順序が `JooqOrderRepository.update` と同じかを確かめる。
違うなら、その経路をモデルに足す。

```bash
grep -rn "tableWriter.update(" backend/src/main/java --include=*.java
```

changeset は `includeAll` で読み込まれるため、新しい changeset は上の差分に出ない。
同期コミット以降に足した changeset が `t_order` か `t_order_line` を変えていないかを、次のコマンドで確かめる。
出たら、その changeset の索引、外部キー、ポリシーが保存の順序の前提を変えないかを読む。

```bash
git diff --name-only --diff-filter=A "$(sed -n 's/^同期コミット：`\([0-9a-f]*\)`.*/\1/p' .agents/skills/formal-verify-aggregate-write/SKILL.md)" -- \
  backend/src/main/resources/db/changelog/changesets | xargs -r grep -l "t_order"
```

## 対応表

モデルは `model/AggregateWrite.tla` にある。
各アクションは一つの SQL の文か、トランザクションの終わりである。

| アクション          | コード                                                                                                                                                                                      |
| ------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ScreenRead`        | 画面が注文を読んで `lock_no` を持つ（前の要求）。それを `expectedLockNo` として送り返す                                                                                                     |
| `Find`              | CommandHandler の `JooqOrderRepository.findById`（multiset の一つの SELECT）と `Order.ensureLockNo`                                                                                         |
| `UpdateRoot`        | `TableWriter.update`（`lock_no = expected` の `UPDATE ... RETURNING`。0 件なら `requireOneRow` が `ConflictException`）                                                                     |
| `ReadSaved`         | `ChildWriter.replace` の保存済みの `line_no` の SELECT と、それに続く文の順序の決定（保存後にない行の DELETE、残る行ごとの UPDATE、新しい行の複数行の INSERT）                              |
| `ChildOp`           | `ChildWriter` の DELETE（保存済みの `line_no` のうち集約にない行）、UPDATE（0 件は `IllegalStateException`）、複数行の INSERT（`uk_t_order_line` の違反は `ConflictException`（`UNIQUE`）） |
| `OpsDone`、`Commit` | `@Transactional` のコミット                                                                                                                                                                 |
| `LockTimeout`       | `lock_timeout`（55P03）。`TableWriter` と `ChildWriter` のすべての文は `ConflictException`（`LOCK`）の 409                                                                                  |
| `Deadlock`          | PostgreSQL のデッドロックの検出（40P01）。どこも変換しない                                                                                                                                  |

## 変異

変異は `model/mutants.toml` にあり、各項目の `change` が写すコードの退行である。

## 範囲と前提

検査の範囲は次のとおりで、これを超える保証はない。

- 集約は一つ、保存する要求は二つ、子の行の鍵は 2 個（`line_no` の 1 と 2）。
- 保存は `update` だけを扱う。
  集約の物理削除と版を比べない書き込みは、`TableWriter` に入口がないため扱わない。
- 集約の INSERT（`add`）と、ルートを書かずに子の行を書く処理（ADR-054 の Negative）は扱わない。
- 読み取りは一つの文（multiset）なので、文の時点のコミット済みの状態を見る。
  読み取りが二つの文に分かれる変更は扱わない。
- 分離レベルは READ COMMITTED だけを扱う。
- `ModuleRoleTransactionManager` のロールとテナントの設定は、トランザクションを始めた直後の `set_config` の一文で、ロックと文の順序を変えないため扱わない。
  テナントを束縛していない業務のトランザクションは、この一文の前に `IllegalStateException` で失敗し、呼び出し側は `CannotCreateTransactionException`（原因が `IllegalStateException`）を受ける。
  業務の SQL は一つも実行されない。
  リクエストはテナントを選ぶ前の業務の API を 403 にするため、保存の経路にはこの拒否が来ず、`OnlyConflictErrors` の対象にならない。
- テナントは一つとし、行のセキュリティ（RLS）のポリシーは扱わない。
  ポリシーは各文に `tenant_public_id` の条件を足すだけで、一つの保存の間テナントは変わらないため、ロックを待つ行と再評価（EvalPlanQual）の結果を変えない。
- `fk_t_order_line`（`tenant_public_id`, `order_id`。019 で足した）を扱わない。
  子の INSERT は親の行を `FOR KEY SHARE` で確かめる。
  `FOR KEY SHARE` は `FOR UPDATE`（DELETE か、一意索引の列を変える UPDATE）だけと衝突する。
  その要求はルートの UPDATE で親の行のロックをすでに持ち、ルートを書く別の要求はそのロックを先に待つため、検査が待ちを増やさない。
  子の行をルートより先に書く順序にしたら、この前提は成り立たない（上の「切り分けの手がかり」の注記と同じ）。

## 既知の発見

なし。

## 同期の記録

同期コミット：`f3f0b7ec5493ba506a2368a42fe98c3caa21fe1e`（main）

このコミットで、「変更がこの検証に関わるか」のファイルと対応表がモデルと一致することを確かめた。
読んだのはこのリポジトリのコードで、PostgreSQL の振る舞いはモデルの冒頭の注記に並べた前提である。
