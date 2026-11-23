# 共通カラムと楽観排他の実装を比べる（#338 の調査）

この文書は #338 の調査の記録で、merge しない。
結論は #338 のコメントと同じで、作業は #661 で行う。
向こうは `yokozi-jp/agent-software-factory`（`origin/main`、commit `3ba2640`）、こちらは `yokozi-jp/spring-modulith-ai-dlc`（`origin/main`、commit `a24fcf3`）である。
調べた日は 2026-11-23 である。

## 結論

一部採用とする。

- 同じ決定：共通カラムの値をアプリがバインドすること、`UPDATE ... WHERE lock_no = n` の更新件数で比べること、0 件のとき行の有無で 404 と 409 に分けること、親子では親の `lock_no` だけを比べて親を先に更新すること。こちらはすでに実装している。
- 向こうの #454（子だけを更新したとき親の `lock_no` を上げることを守らせる）：こちらは構造で守っている。`TableWriter.update` が唯一の入口で、子の行を書く前に必ず親の行を更新して `lock_no` を進める。子だけを書く入口がない。実 PostgreSQL（READ COMMITTED）で確かめ、既存のテストが退行を検出することも確かめた。
- 取り込む：集約を宣言する段の穴を塞ぐ検査。子のテーブルを独立のルートとして宣言した場合と、`ChildTable` の `parentKey` を誤った場合は、親の `lock_no` を進めずに子だけを書ける。今の検査はどちらも通す。DB の外部キーと宣言を突き合わせて止める。モデル検査（`formal-verify-aggregate-write`）に、親の版を進めない変異を足す（#661）。
- 取り込まない：`lock_no` の DB 既定値、`patchedColumns` に当たる関数、`actor`、`pgmCd`、`txId` を scope で渡す形、静的解析で子の更新の SQL を調べる案。

理由は 3 つある。

1. #454 の案 A（親子の更新を 1 つの関数に通す）に当たる形が、`TableWriter.update` としてすでにある。向こうは `updateWithLock` が部品だけで、親子の規則は docs にしかない（PR #451 の本文も「コードの仕組みはない」と書く）。
2. 残る穴は、宣言と DB の外部キーが食い違う場合だけである。こちらは同じモジュールの表どうしに外部キーを置くので、カタログから親子の関係を読める。向こうは外部キーを置かないので、案 B にカタログを使えなかった。こちらでは安い検査で止められる。
3. 子だけの更新で親の版が進まない退行は、既存のテストが検出する。専用のテストを足す必要はなく、足りないのは宣言の検査とモデル検査の変異である。

## 比べたもの

### 向こう

- `packages/shared-kernel/src/tenant-db/common-columns.ts`、`optimistic-lock.ts`、`insert-row.ts`、`tenant-transaction.ts`
- `docs/db/postgresql-keys.md`（「このリポジトリでは」、168 行から）、`docs/db/postgresql-concurrency.md`（「このリポジトリでは」）、`docs/db/convention-check.md`
- PR #451（merge 済み。`feat(shared): 共通カラムの値を入れる関数と楽観排他の更新を置く`）、Issue #454（open。案 A、B、C を挙げ、まだ選んでいない）

### こちら

- ADR-051（`pgm_cd`）、ADR-054（`lock_no` と `TableWriter`）、ADR-096（`created_by` の利用者）
- `docs/database/postgresql-common-columns.md`、`postgresql-concurrency-control.md`、`postgresql-constraints-and-indexes.md`
- `backend/src/main/java/com/example/demo/shared/infrastructure/persistence/` の `TableWriter`、`ChildWriter`、`CommonColumns`、`AggregateTable`、`ChildTable`
- `JooqOrderRepository`、`JooqTenantRepository`、`JooqUserRepository`、`JooqPaymentRepository`
- `AggregateDeclarationsTest`、`TableWriterTest`、`TableWriterConcurrencyTest`、`JooqTenantRepositoryTest`
- `.agents/skills/formal-verify-aggregate-write`（`SKILL.md` とモデル）

## 項目ごとの比較

| 向こうの規則                                                                                     | 向こうの場所                                                                     | こちらの扱い                                                                                           | 判定                       |
| ------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ | -------------------------- |
| 共通カラムの値はアプリがバインドする。`CURRENT_TIMESTAMP` とトリガーを使わない                   | `postgresql-keys.md`「共通カラムの値」                                           | `postgresql-common-columns.md`「値の登録」                                                             | 同じ決定                   |
| 時刻は入口が 1 回読み、トランザクションの INSERT と UPDATE のすべてが同じ値を使う                | `common-columns.ts:14-18`、`postgresql-keys.md`                                  | `CommonColumns.audit()` が `forInsert` と `forUpdate` のたびに `Instant.now(clock)` を読む             | 取り込み済み（#322、#584） |
| `lock_no` だけ DB の既定値 1。INSERT では `created_*` と `updated_*` を入れる                    | `common-columns.ts:31-34`、`postgresql-keys.md`                                  | 既定値なし。`TableWriter.insert` が `lock_no = 1` を書く                                               | 取り込まない               |
| `updated_*` と `lock_no = lock_no + 1` を返す `updatedColumns`                                   | `common-columns.ts:46-56`                                                        | `forUpdate` が `updated_*` だけを返し、`lock_no = 期待値 + 1` は `TableWriter` だけが書く（ADR-054）   | 同じ決定（表現が違う）     |
| `patchedColumns`：`patched_*` だけを書き、`updated_*` と `lock_no` は触らない                    | `common-columns.ts:58-66`                                                        | データパッチは psql と Liquibase で行い、Java に書く入口がない                                         | 取り込まない               |
| `actor`、`pgmCd`、`txId` を `inTenantTransaction` の scope で渡す                                | PR #451 の本文、`tenant-transaction.ts`                                          | `pgm_cd` は Aspect と `ScopedValue`（ADR-051）、`*_by` は principal（ADR-096）、`*_tx_id` は trace ID  | 取り込まない               |
| `UPDATE ... WHERE <id> AND lock_no = $n RETURNING`。0 行なら SELECT し直し、行なし 404、あり 409 | `optimistic-lock.ts:12-45`                                                       | `TableWriter.update`（`:104-140`）と `requireOneRow`（`:144-`）                                        | 同じ決定                   |
| 別のテナントの行は RLS で見えず 404                                                              | `optimistic-lock.ts:22-23`                                                       | RLS が全業務テーブルで効く（`TenantIsolationTest`）。`update` の存在確認も同じ接続で RLS を受ける      | 同じ決定                   |
| 親子では、親の `lock_no` だけを比べ、親への UPDATE を最初に実行する                              | `postgresql-concurrency.md`（「案 1 の弱点の扱い」）、`optimistic-lock.ts:26-27` | `update` が親を先に更新し、子は差分で書く（`postgresql-concurrency-control.md` の「親子のテーブル…」） | 同じ決定                   |
| 子だけを更新するときも、親の `lock_no` を上げる                                                  | 同上。コードにも検査にもない（Issue #454）                                       | `update` が親を必ず更新する。子だけを書く入口がない                                                    | こちらが実装済み（後述）   |
| 外部キーを置かない                                                                               | `postgresql-keys.md`、`convention-check.md` の `foreign-key`                     | 同じモジュールの表どうしに外部キーを置く（`postgresql-constraints-and-indexes.md`、K1）                | 違う前提。検査に使える     |

## #454 への答え

### 向こうの問い

子だけを更新するコードが親の `lock_no` を上げ忘れると、別の画面が同じ親を更新した結果を上書きしても 409 にならない。
上げ忘れは、型検査、lint、テストのどれでも見つからない。
案は、A（親子の更新の関数を `packages/shared` に置く）、B（子の更新の SQL を静的に調べる lint）、C（レビューで見る）である。

### こちらの構造

- 業務テーブルの UPDATE は `TableWriter.update` だけが書く。jOOQ の書き込み、Spring JDBC、JDBC の直接の利用は、`TableWriter` と `ChildWriter` の外で ArchUnit の規則 H1 が止める（ADR-054）。
- `ChildWriter` は package-private で、呼ぶのは `TableWriter` だけである（`ChildWriter.java:26`、`TableWriter.java:139`）。
- `update` は、先に親の行を `SET 業務の列, updated_*, lock_no = 期待値 + 1 WHERE 一意の列 AND lock_no = 期待値` で更新し、1 行でなければ例外にする。その後に子を書く（`TableWriter.java:104-140`）。業務の列が一つもなくても、親の版を進める。
- 親の更新が子の書き込みより先なので、同じ集約を書く別の保存は、親の行ロックで待たされる。待った後は更新後の行で `WHERE` を評価し直し、版が違えば 0 件になる。
- `JooqTenantRepository.update` は、所属（子）だけを変えるときに `set -> {}`（親の業務の列なし）で呼ぶ。コメントも「所属だけの変更でも、ルートの版を進める」と書く（`:78`）。

これは #454 の案 A に当たり、子だけを書く別の入口を持たないので、案 A より強い。

### 実 PostgreSQL で確かめたこと

PostgreSQL 18.6（`docker/compose-test.yml`）の Testcontainers で、`TableWriter` に fixture の表（`fixture_item` と `fixture_item_detail`）を宣言して動かした。
2 つの接続 A と B を使い、どちらも親の `lock_no` が 1 の行を読んだ後の保存を試した。
この試験は commit せず、調査のために書いた。

| 試験                                                                  | 結果                                                                                                            |
| --------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| 接続の分離レベル                                                      | `show transaction_isolation` が `read committed`                                                                |
| B が子だけを変えて（親の業務の列なし）commit する                     | 親の `lock_no` が 1 から 2 になり、子の行は B の値になった                                                      |
| その後に A が版 1 で子だけを保存する                                  | `ConflictException`（`row was updated by another request`）。B の子の変更は残り、親は 2 のまま                  |
| B が commit せずに親の行ロックを持つ間に、A が同じ保存を始める        | `pg_stat_activity` で A が `Lock` を待つことを確かめ、B の commit 後に A は `ConflictException`。B の変更は残る |
| 対照：B が子の行だけを SQL で更新し、親の `lock_no` を 1 のままにする | A（版 1）の保存が成功し、B の変更が消えた（更新の消失）                                                         |

PostgreSQL の文書（13.2.1 Read Committed Isolation Level）は、`UPDATE` が他のトランザクションの更新を待ち、commit されたら更新後の行に対して検索条件（`WHERE`）を評価し直すと定める。
上の 3 つ目と 4 つ目は、この挙動と一致する。

### 退行をテストが検出すること

`TableWriter.update` を一時的に次の 2 つに変えて、`TableWriterTest`、`TableWriterConcurrencyTest`、`JooqTenantRepositoryTest`、`JooqOrderRepositoryTest` を流した。変異は戻した。

- `.set(lockNo, ...)` の行を消す（親の版を進めない）：既存のテスト 6 件が落ちた。`TableWriterTest` が 3 件、`JooqTenantRepositoryTest` が 2 件、`JooqOrderRepositoryTest` が 1 件である。
- 業務の列がないとき（子だけの変更）だけ、親の版を進めない：既存のテスト 3 件が落ちた。`TableWriterTest` の「update は業務の列がなくても、版と updated_* を進める」と、`JooqTenantRepositoryTest` の 2 件である。

調査用の試験の 3 件（子だけの変更で親の版が進むこと、古い保存が 409 になること、待った後に 409 になること）も、どちらの変異でも落ちた。
向こうの #454 の受け入れ条件「わざと上げ忘れる fixture で検出力を確かめる」は、こちらでは既存のテストで満たされている。

### 残る穴：集約の宣言

子だけの更新が親の版を進めないのは、宣言が実際の親子と食い違うときである。

- 子のテーブルを、親の `ChildTable` として宣言せず、独立の `AggregateTable` として宣言する。その Repository の `update` は、子の表の行の版しか進めない。
- `ChildTable` の `parentKey` に、親への外部キーでない列を渡す。

`AggregateDeclarationsTest` が見るのは、同じ子の二重の宣言と、ルートと子の重複だけで、どちらの誤りも通る。
ADR-054 の Negative（`:179`）は、`parentKey` が親のテーブルを指すかを確かめないと書き、`jooq-repository.md:403` のチェックリストは「自分で点検」とする。
今の 4 つの集約は、どれも正しく宣言されている。

### 取り込む検査の試作

こちらは同じモジュールの表どうしに外部キーを置き（`postgresql-constraints-and-indexes.md`、K1 がその有無を検査する）、カタログから親子の関係を読める。
向こうは外部キーを置かず、`convention-check.md` の `foreign-key` 検査が外部キーを禁じるので、案 B でカタログを使えなかった。

DB のカタログの外部キーと、本番の `AggregateTable` の宣言を突き合わせる試作を書いて流した（commit しない）。
外部キーは `pg_constraint`（`contype = 'f'`）から、業務の 4 つのスキーマについて読んだ。

規則 1：外部キーの「参照する表 → 参照される表」ごとに、参照する表が、参照される表をルートとする集約の子として宣言されているか、許可の一覧にある。

- 実在の宣言：違反は 0 件だった。許可が要ったのは `tenancy.m_membership` から `tenancy.m_user` の 1 件だけである。
- 変異：`t_order_line` を `ORDER` の子から外し、独立のルートとして宣言すると、`ordering.t_order_line -> ordering.t_order` を報告した。
  このとき `AggregateDeclarationsTest` の規則（子の重複なし、ルートと子の重複なし）は通る。

規則 2：宣言した `ChildTable` の `parentKey` の列が、親への外部キーの列に含まれる。

- 実在の宣言：違反は 0 件だった。`t_order_line` の外部キーは `(tenant_public_id, order_id)` の複合で、`order_id` を含む。
- 変異：`m_membership` の `parentKey` を `user_id` にすると、`tenancy.m_membership parentKey=user_id fkCols=tenant_id` を報告した。

`tenancy.m_membership` から `tenancy.m_user` は、所属が別の集約（利用者）を指す参照で、所属は `m_tenant` の子である。
許可の一覧に理由を付けて載せる。
この一覧は、外部キーが増えるたびに、親子か別の集約への参照かを書き手に決めさせる。

### モデル検査

`formal-verify-aggregate-write` のモデルは、ルートの `UPDATE` を保存の最初の文として置き、`NoLostUpdate`、`AggregateWhole`、`OnlyConflictErrors` を検査する。
変異は `children-before-root`、`unversioned-root`、`no-delete-of-removed-lines` の 3 つで、親の版を比べるが進めない変異がない。

スキルのディレクトリを `/tmp` に写し、変異 `unbumped-root`（`UpdateRoot` が `lock |-> w[i].expected + 1` でなく `w[i].expected` にする）を足して `tooling/formal-verify/mutants.py` を流した。
対照（変異なし）は `two-saves` で `NoLostUpdate` を満たし、変異ありは `NoLostUpdate` の違反を TLC が報告した（`caught`）。既存の 3 つの変異も `caught` のままだった。
モデルの保存は、ルートの業務の列を持たず、子の行だけが変わりうるので、この変異が「子だけの変更で親の版が進まない」退行に当たる。

作業の Issue（#661）は、`TableWriter`、`ChildWriter`、`ChildTable` を変えない。
変異を足すので、`check.sh` と `mutants.py` を受け入れ条件に入れた。
これらのファイルを変えるときは、`SKILL.md` の同期コミットを更新する。

## 取り込まないもの

- **`lock_no` の DB 既定値 1**：こちらの規約は、すべての共通カラムをアプリがバインドし、例外を作らない（`postgresql-common-columns.md`「既定値を持たない理由」）。INSERT の入口は `TableWriter.insert` だけ（H1）で、`lock_no = 1` を必ず書くので、既定値で補う場面がない。
- **`patchedColumns` に当たる関数**：こちらのデータパッチは psql と Liquibase の SQL で当て、Java に書く入口がない（ADR-054 の Negative が、Java の外の書き込みを受け入れたリスクとする）。呼ぶコードがない関数は足さない。`patched_id` の型も違う（向こうは文字列の課題番号で、こちらは `integer`）。
- **`actor`、`pgmCd`、`txId` を scope で渡す形**：向こうは、これらを誰が決めるかを「入口の雛形の Issue」に残している（PR #451 の本文）。こちらは `pgm_cd` を Aspect と `ScopedValue`（ADR-051）、`*_by` を認証の principal（ADR-096）、`*_tx_id` を trace ID で決め、`CommonColumns` が束縛されていなければ書き込みを失敗させる。
- **子の更新の SQL を静的に調べる lint（#454 の案 B）**：外部キーと宣言を突き合わせる検査で足りる。静的な解析は誤検出が増えると #454 自身が書く。
- **#454 の案 C（レビューで見る）**：検査で止められる。
- **向こうの `updatedColumns` の `lock_no = lock_no + 1`**：向こうは `WHERE lock_no = $n` と組で使い、待った後の再評価で `WHERE` が先に成り立つので、結果は `期待値 + 1` と同じになる。こちらの式のままにする。ADR-054 の Context が、`lock_no + 1` の加算だけでは古い保存を止められないと書くので、`WHERE` の比較を欠く形を、型で作れないほうがよい。

## フレームワークとライブラリの仕組みに寄せられるか

こちらの永続化は jOOQ（ADR-003）で、ORM の仕組みは使わない。参考に調べた。

- Hibernate は、`LockModeType.OPTIMISTIC_FORCE_INCREMENT` で、変更のない entity の版を進める（Hibernate 7.0 User Guide、11.3）。子だけの変更で親の版を進めたいときの明示の仕組みである。
- Spring Data JDBC は、`@Version` を集約ルートに付け、「更新のたびに版を増やす」（Spring Data Relational のリファレンス、JDBC の Mapping）。子だけを変えたときにルートの版が進むかは、読んでいない。
- jOOQ の楽観ロックは `UpdatableRecord` の `store()` と `delete()` に効き、DSL の `UPDATE` には効かない（ADR-054 の選択肢 4）。jOOQ 3.21.10（最新）のリリースノートのページを「optimistic」で探したが、DSL の `UPDATE` に効く機能の追加は見つからなかった。こちらは 3.21.9 である。

ORM へ移ると集約の保存の方式が変わり、ADR-003 と ADR-054 を改める規模になる。この調査では選ばない。

## 限界

- 実測は、fixture の表（`fixture_item` と `fixture_item_detail`）に `TableWriter` を当てたものである。本物の `t_order` と `t_order_line`、`m_tenant` と `m_membership` は、既存のテスト（`JooqOrderRepositoryTest`、`JooqTenantRepositoryTest`）が確かめる。
- 並行の試験は 2 つの接続だけで、3 つ以上の同時の保存は試していない。モデル検査の範囲も、保存する要求は 2 つである（`SKILL.md` の「範囲と前提」）。
- 外部キーの検査は試作で、実装していない。`SchemaCatalog.read` を使い回せるかは確かめていない（`CatalogSnapshot` は別のパッケージで package-private）。
- 外部キーがない親子（外部キーを作り忘れた子のテーブル）は、この検査では見つからない。K1 が「同じスキーマのほかの表の主キーと同じ名前の列」に外部キーを求めるので、列の名前が主キーと違う場合は漏れる。
- psql と Liquibase による Java の外の書き込みは、ADR-054 が受け入れたリスクのままで、この検査の対象外である。
- Spring Boot の最新は 4.2.0（Maven Central の `spring-boot`）で、こちらは 4.1.2 である。4.2 のリリースノートは取得できず（GitHub の wiki が空で返った）、読んでいない。この調査が頼る機能（`DSLContext`、トランザクション）に関わる変更は確かめていない。
- PostgreSQL は、こちらの試験が 18.6、文書は 18 の現行版を読んだ。18.7 のリリースノートは読んでいない。
- 向こうの `tenant-transaction.ts`、`common-columns.test.ts`、`tenant-transaction.test.ts` は読んでいない。PR #451 の Evidence（testcontainers での実行結果）は本文を読んだだけで、向こうのコードは動かしていない。
- 別のテナントの行を `TableWriter.update` に渡したとき 404 になる試験は、こちらには見つけていない。RLS の 0 件の更新は `TenantIsolationTest` が全業務テーブルで確かめるが、`TableWriter` を通した結合は確かめていない。

## 確かめたこと

- 向こうの `common-columns.ts`、`optimistic-lock.ts`、`insert-row.ts`、`docs/db/postgresql-keys.md`、`postgresql-concurrency.md`、`convention-check.md` を `origin/main`（`3ba2640`）から読んだ。パスは、ticket の `tenant-db/` が `packages/shared-kernel/src/tenant-db/` にある（PR #451 は `packages/shared` と書き、その後 `shared-kernel` に改名された。commit `dd9054f`）。
- Issue #451（PR）と #454 の本文、コメントを読んだ。#454 は open で、案を選んでいない。
- こちらの ADR-051、ADR-054 の全文と、`postgresql-common-columns.md`、`postgresql-concurrency-control.md` を読んだ。
- `TableWriter`、`ChildWriter`、`CommonColumns`、4 つの Repository、`AggregateDeclarationsTest`、`TableWriterTest`、`TableWriterConcurrencyTest` を読んだ。`m_membership` を書くのは `JooqTenantRepository.update` だけである（`JooqMembershipRepository` は読み取りだけ）。
- 本番の `AggregateTable` の宣言は 4 つ（`m_tenant` の子 `m_membership`、`t_payment`、`m_user` の子 `m_user_identity`、`t_order` の子 `t_order_line`）で、業務の 4 つのスキーマの外部キーは 4 本（`t_order_line` → `t_order`、`m_membership` → `m_tenant`、`m_membership` → `m_user`、`m_user_identity` → `m_user`）である。
- 試験（`ChildOnlyResearchTest` と `FkDeclarationResearchTest`）は commit していない。上の表の結果は、これを `./gradlew test --tests ...` で流して得た。
- PostgreSQL 18、13.2.1 の文を読んだ。Hibernate 7.0 の User Guide、Spring Data Relational のリファレンス、jOOQ のリリースノートのページを読んだ。

## 出典

- [PostgreSQL 18: 13.2.1 Read Committed Isolation Level](https://www.postgresql.org/docs/18/transaction-iso.html#XACT-READ-COMMITTED)
- [Hibernate ORM 7.0 User Guide（Locking、LockModeType）](https://docs.hibernate.org/orm/7.0/userguide/html_single/Hibernate_User_Guide.html)
- [Spring Data Relational: JDBC の Mapping（`@Version`、集約ルート）](https://docs.spring.io/spring-data/relational/reference/jdbc/mapping.html)
- [jOOQ release notes](https://www.jooq.org/notes)
- 向こう：`packages/shared-kernel/src/tenant-db/common-columns.ts`、`optimistic-lock.ts`、`insert-row.ts`、`docs/db/postgresql-keys.md`、`postgresql-concurrency.md`、`convention-check.md`、PR #451、Issue #454
- こちら：ADR-003、ADR-051、ADR-054、ADR-096、`docs/database/postgresql-common-columns.md`、`postgresql-concurrency-control.md`、`postgresql-constraints-and-indexes.md`、`docs/backend/class-roles/jooq-repository.md`、`backend/src/main/java/com/example/demo/shared/infrastructure/persistence/`、`.agents/skills/formal-verify-aggregate-write/`

## 作業の Issue

- #661：集約の宣言を外部キーと突き合わせ、子だけの更新が親の `lock_no` を進めない宣言を止める。モデル検査に `unbumped-root` の変異を足す。
