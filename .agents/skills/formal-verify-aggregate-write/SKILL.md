---
name: formal-verify-aggregate-write
description: 集約の保存（TableWriter.updateCheckingVersion、LockedRoot の子の行の書き込み、JooqOrderRepository.update の親を先に書く順序）を、PostgreSQL の READ COMMITTED と行ロックのモデルで、二つの保存の全順序について TLA+ で検査する。TableWriter、LockedRoot、DeletedRoot、Repository の update、子のテーブルの一意制約、分離レベル、docs/database/postgresql-concurrency-control.md の保存の順序を変えるときと、更新の消失、集約の混ざり、デッドロック、422 や 500 の競合を疑うときに使う。
---

# 集約の保存の形式検証

同じ集約（親の行と子の行）を二つの要求が同時に保存するときの、すべての順序を検査する。
道具は TLA+ の TLC である。
問いが「二つのトランザクションの文がどう交互に並んでも、楽観的ロックと行ロックで保存が正しく直列になるか」であり、テストでは文の間の割り込みとロック待ちの解け方を網羅できないためである。

モデルにした保存の経路は、今の唯一の呼び出し元である `JooqOrderRepository.update` である。
`docs/database/postgresql-concurrency-control.md` の「親子のテーブルの保存の順序」に従う Repository は同じ形になるため、新しい集約もこの検査の範囲に入る。

## 約束（不変条件）

| 名前                 | 約束                                                                                  | 破れたときの害                                   |
| -------------------- | ------------------------------------------------------------------------------------- | ------------------------------------------------ |
| `NoLostUpdate`       | コミットした二つの保存が、同じ版（`lock_no`）から始まらない。                          | 先の保存の変更が、後の保存で黙って消える。       |
| `AggregateWhole`     | コミットした集約の子の行は、最後にコミットした一つの保存の子の行と一致する。          | 二つの保存の明細が混ざった注文が残る。           |
| `OnlyConflictErrors` | 競合に負けた保存は、再読み込みを促す 409（`ConflictException` の `VERSION` か `LOCK`）だけで失敗する。 | 利用者に 422 や 500 が返り、原因が分からない。   |

## 実行する

リポジトリのルートで実行する。

```bash
.agents/skills/formal-verify-aggregate-write/scripts/check.sh
.agents/skills/formal-verify-aggregate-write/scripts/mutants.py
```

- `check.sh` は `model/*.cfg` をすべて検査し、各設定の 1 行目の `expect:` と結果が一致すれば 0 で終わる。数秒で終わる。
  初回だけ TLC（`tla2tools.jar` v1.7.4、SHA-256 で確かめる）を `~/.cache/formal-verify-tla/` に取得し、Java（`temurin-25.0.5+8.0.LTS`）を mise で入れるため、ネットワークが要る。
  Kiro Web では先に `source docs/local-env-setup/kiro-web.sh` を実行する。
- `mutants.py` は、モデルの写しにコードの退行を一つずつ入れ、それぞれが守る不変条件を TLC が破ると報告することを確かめる。
  一つでも見逃すと 1 で終わる。モデルを変えたら必ず実行する。

| 設定             | 内容                                                                   | 期待する結果         |
| ---------------- | ---------------------------------------------------------------------- | -------------------- |
| `two-saves.cfg`  | 二つの要求が、最初の明細と保存する明細のどの組み合わせでも同時に保存する | 全不変条件が成り立つ |

## 結果を読む

TLC の結果は必ず `/statechart` スキルで読む。
不変条件の違反は最短の経路として平易な言葉で示され、成り立つときは状態の遷移図を描く。
`/statechart` はリポジトリの外の作業用のフォルダで動かす。
`model/` を写し、状態と遷移の言葉を決める view を書いて渡す（書き方は `/statechart` にある）。

違反が出たら、本物の不具合かモデルの誤りかを次の順に切り分ける。

1. 経路の各ステップを、下の「対応表」でコードの場所に置き換える。
2. 各ステップがコードで一つの SQL の文か、その文が PostgreSQL でその結果になるかを確かめる。
   モデルの冒頭の注記に、モデルが頼る PostgreSQL の振る舞いを並べてある。
3. 起こりうるなら本物の不具合である。不変条件やモデルを緩めて通さない。
   経路をコードの言葉の手順で利用者に報告し、製品のコードは利用者が頼んだときだけ直す。
   結合テストで、二つのトランザクションをその順序で動かして再現できることが多い。
4. 起こりえないなら、モデルのどの行がコードと違うかを直し、`check.sh` と `mutants.py` を流し直す。

## 変更がこの検証に関わるか

次のファイルの差分があれば関わる。

```bash
git diff --stat <同期コミット> -- \
  backend/src/main/java/com/example/demo/shared/infrastructure/persistence/TableWriter.java \
  backend/src/main/java/com/example/demo/shared/infrastructure/persistence/LockedRoot.java \
  backend/src/main/java/com/example/demo/ordering/infrastructure/persistence/JooqOrderRepository.java \
  backend/src/main/java/com/example/demo/ordering/domain/model/Order.java \
  backend/src/main/java/com/example/demo/ordering/application/ChangeOrderLinesCommandHandler.java \
  backend/src/main/java/com/example/demo/ordering/application/ConfirmOrderCommandHandler.java \
  backend/src/main/java/com/example/demo/ordering/application/CancelOrderCommandHandler.java \
  backend/src/main/resources/db/changelog/changesets/004-create-order-tables.yaml \
  backend/src/main/resources/application.yaml \
  docs/database/postgresql-concurrency-control.md
```

`application.yaml` は `connection-init-sql`（`lock_timeout` など）だけが関わる。
`updateCheckingVersion` か `deleteCheckingVersion` の新しい呼び出し元（ほかの集約の Repository）ができたら、その保存の順序が `JooqOrderRepository.update` と同じかを確かめる。
違うなら、その経路をモデルに足す。

```bash
grep -rn "updateCheckingVersion\|deleteCheckingVersion" backend/src/main/java --include=*.java
```

## 対応表

モデルは `model/AggregateWrite.tla` にある。
各アクションは一つの SQL の文か、トランザクションの終わりである。

| アクション     | コード                                                                                                                       |
| -------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| `ScreenRead`   | 画面が注文を読んで `lock_no` を持つ（前の要求）。それを `expectedLockNo` として送り返す                                     |
| `Find`         | CommandHandler の `JooqOrderRepository.findById`（multiset の一つの SELECT）と `Order.ensureLockNo`                          |
| `UpdateRoot`   | `TableWriter.updateCheckingVersion`（`lock_no = expected` の UPDATE。0 件なら `requireOneRow` が `ConflictException`）     |
| `ReadSaved`    | `JooqOrderRepository.update` の保存済みの `line_no` の SELECT と、それに続く文の順序の決定                                    |
| `ChildOp`      | `LockedRoot.deleteChildren`、`LockedRoot.updateChild`（0 件は `BusinessRuleViolationException`）、`insertLine(...).execute()`（`uk_t_order_line` の違反は変換されない） |
| `OpsDone`、`Commit` | `@Transactional` のコミット                                                                                             |
| `LockTimeout`  | `lock_timeout`（55P03）。`TableWriter` が `ConflictException`（`LOCK`）に変える                                             |
| `Deadlock`     | PostgreSQL のデッドロックの検出（40P01）。どこも変換しない                                                                   |

変異（`scripts/mutants.py` の `MUTANTS`）と、それが写すコードの退行は次のとおり。

| 変異                         | コードの退行                                                                       | 破れる不変条件       |
| ---------------------------- | ---------------------------------------------------------------------------------- | -------------------- |
| `children-before-root`       | `JooqOrderRepository.update` が子の行を書いてから親を `updateCheckingVersion` する | `OnlyConflictErrors`（デッドロック） |
| `unversioned-root`           | 親の行を `updateWhere`（`lock_no = lock_no + 1`、版を比べない）で書く              | `NoLostUpdate`       |
| `no-delete-of-removed-lines` | 集約にない子の行の `deleteChildren` を落とす                                       | `AggregateWhole`     |

## 意図した変更でモデルを直す

1. 変えたコードを対応表で引き、対応するアクションをコードの新しい振る舞いに合わせて書き換える。
   新しい SQL の文が増えたらアクションを足し、`Next` と対応表に足す。
2. 新しい約束が増えたら不変条件を足し、それを破る変異を `MUTANTS` と上の表に足す。
3. `check.sh` と `mutants.py` を流し、違反が出たら「結果を読む」で切り分ける。
4. コードとモデルが一致することを確かめてから、下の同期コミットを新しいコミットに進める。
   確かめていないコミットへ進めない。

## 範囲と前提

検査の範囲は次のとおりで、これを超える保証はない。

- 集約は一つ、保存する要求は二つ、子の行の鍵は 2 個（`line_no` の 1 と 2）。
- 保存は `update` だけを扱う。`deleteCheckingVersion` と `DeletedRoot` は今は呼び出し元がないため扱わない。
- 集約の INSERT（`add`）、版を持たない書き込み（`updateWhere`、`deleteWhere`）と、ルートを書かずに子の行を書く処理（ADR-054 の Negative）は扱わない。
- 読み取りは一つの文（multiset）なので、文の時点のコミット済みの状態を見る。読み取りが二つの文に分かれる変更は扱わない。
- 分離レベルは READ COMMITTED だけを扱う。
- `ModuleRoleTransactionManager` のトランザクションごとのロールの切り替えは、ロックと文の順序を変えないため扱わない。

## 同期の記録

同期コミット：`cf79ec53c58d561e357b3e6c8dcd5af02852e058`（ブランチ `fix/modulith-staleness-from-last-resubmission`。main に入ったら、そのマージのコミットで確かめ直して進める）

このコミットで、「変更がこの検証に関わるか」のファイルと対応表がモデルと一致することを確かめた。
関わるファイルがこのコミットより後に変わっていたら、モデルを読み直してから検査の結果を信じる。
読み直しには `/maintain-formal-verification-skill` を使う。
