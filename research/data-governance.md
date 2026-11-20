# データガバナンスの docs を取り込むか（#326 の調査）

この文書は #326 の調査の記録で、merge しない。
結論は #326 のコメントと同じで、作業は作業の Issue で行う。
向こうは `yokozi-jp/agent-software-factory`（`origin/main`、commit `29ba897`）、こちらは `yokozi-jp/spring-modulith-ai-dlc`（main、commit `63e5c9f`）である。

## 結論

一部採用とする。
向こうの `docs/data-governance/` は、フューチャー株式会社のデータガバナンス設計ガイドラインを、ほぼそのまま 18 の文書に分けて置いたものである（`about.md:17-25`）。
原文は、全社のデータ基盤（Snowflake、BigQuery など）を前提にし（`about.md:86`）、部署をまたぐデータの二次利用のルールと体制を扱う（`about.md:81-85`）。
こちらはデータ基盤も、部署をまたぐ二次利用者もない業務アプリなので、ほとんどの章はこちらに当てはまらない。

当てはまるのは、データの意味をスキーマに書く規則と、個人データを最初から持たない原則の 2 つで、これを取り込む。

- 業務の表と列に、日本語の論理名をコメント（Liquibase の `remarks`、PostgreSQL の `COMMENT ON`）で最初から書く。付け忘れは、既存のスキーマ検査（`SchemaInspectionTest`）で止める。
- 業務に要らない個人データの列を作らない。IdP が持つ利用者の属性を、業務で要るとき以外は写さない。

データ分類（L1〜L4）と用途制約フラグ、マスキングの基準と手法の表、データカタログの立ち上げと運用、データ品質の SLA、マスタデータ管理、データ基盤への連携、組織変更と権限のライフサイクル、推進体制と費用負担と評価指標、技術トレンド、AI-Ready は取り込まない。
ADR-040 はデータガバナンスを取り込まない原文に挙げている（ADR-040:39）ので、作業の Issue で ADR-040 を直す。

## 向こうでの扱い

- 向こうは原文を `docs/data-governance/` に置いた（commit f4691ae、yokozi-jp/agent-software-factory#19）。
  `docs/index.md:16` の入口があるだけで、ほかの docs、ADR、コードからの参照はない（`git grep data-governance` で `docs/` の外に参照なし）。
- 向こう自身も表と列にコメントを書いていない。`packages/db/migrations/*.sql` に `COMMENT ON` はなく、DB のカタログの検査（`docs/db/convention-check.md`）もコメントを見ない。
- Data Contract は yokozi-jp/agent-software-factory#214 で取り込まないと決めた。理由は、Data Contract がデータ基盤の上で別のチームがデータプロダクトを使う場面の道具で、原文も業務システムの生データには中身の品質の SLA を課さないとしている（`data-quality.md:60-63`）ことである。
  代わりに、モジュール間のイベントは型の共有と項目の追加だけの変更と過去の版の fixture のテスト、会計システムへの CSV は zod の行のスキーマとスナップショットのテストにした。

## 同じ決定になっているもの

- **スキーマ変更は後方互換で足す**（`data-quality.md:65-67`、「カラム追加のみとしたり、並行稼働期間を持たせる」）：
  こちらは、API を後方互換で改修し（`docs/web-api/versioning.md:10`）、イベントは省略できる項目の追加だけで変えて過去の形の fixture で確かめる（#312、#560）。
  向こうの #214 の Q2 とも同じである。
- **中身の品質は源泉の入力検査で守る**（`data-quality.md:53`、`:62`）：こちらは入力検証を API の境界と Domain に置く（`docs/web-api/validation.md`）。
- **テーブル定義の正本はコード**（`metadata.md:48`、「正となるデータは DDL など」）：こちらは Liquibase の changeset を正本にし、テーブル定義書を別に作らない（`docs/writing/design-doc-sources-of-truth.md:25`）。
- **外に出すデータは最初から渡さない**（`data-masking.md:62`、「特定を防ぐ最も確実な方法は最初から持たないこと」）：
  こちらは、ログに個人データを渡さず（`docs/observability/conventions.md:50-66`）、イベントの本文に個人情報を入れず（`docs/integration/async-message-security.md:26`）、連携ファイルに不要な機密情報を含めない（`docs/integration/interface-file-security.md:21`）。
  どれも、外に出してからマスクするより、渡さない方を選んでいる。
- **表が個人情報を含むかを記録する**（`data-masking.md:83`、マスキング列の指定）：こちらは表の単位で、保持期限と同じ一覧に書くと決めている（`docs/writing/design-doc-sources-of-truth.md:41`）。
- **差分の抽出に、行の更新日時でなく業務の日時を使う**（`metadata.md:106-123`、「最新連携日時」と「メタデータ更新日時」を分ける）：こちらは、差分の抽出に共通カラムの `updated_at` を使わず業務の更新日時のカラムを定義する（`docs/database/postgresql-data-integration.md:31`）。
- **マスタの統合は、複数のシステムが同じマスタを編集するときだけ考える**（`master-data.md`）：こちらも同じ条件で別に検討するとしている（`docs/database/postgresql-data-integration.md:22`）。

## 取り込むもの

### 業務の表と列に、日本語の論理名をコメントで最初から書く

原文は、データカタログのツールを入れる前から、表と列の `description` を最初から必ず書き、最低限は日本語の名前にし、それ以上は求めすぎないとする（`metadata.md:30-37`、原文 `data_governance_guidelines.md:635-637`、commit `e309a6d`）。
理由は、後から書いてもらうのが難しいこと、問い合わせを減らすこと、AI の利活用に効くことである。

こちらの今の状態は次のとおりである。

- changeset に `remarks` が 1 つもない（`backend/src/main/resources/db/changelog/changesets/` の 001〜014）。`docs/database/postgresql-data-types.md:83` が JSON のカラムの `remarks` に JSON Schema の場所を書くと決めているだけである。
- 論理名の置き場所がない。`docs/database/postgresql-naming.md:24-26` は論理名と物理名の対応を論物変換辞書で持つとするが、辞書はまだない。テーブル定義書は作らない（`design-doc-sources-of-truth.md:25`）。
- 業務の表は 4 つのスキーマに 8 つ、列は 133 ある（jOOQ の生成物の `TableField` の数。`modulith` スキーマを除く）。

こちらでは、changeset を正本のまま、論理名を changeset に書く形にする。

- 新しい表は `createTable` と `column` の `remarks` に書く。既存の表は、`setTableRemarks` と `setColumnRemarks` の changeset を 1 つ足す（適用した changeset の中身を変えると、Liquibase が checksum の不一致で止まるため）。
- Liquibase 5.0.4 は、`setTableRemarks` と `setColumnRemarks` を PostgreSQL の `COMMENT ON TABLE` と `COMMENT ON COLUMN` にする（下の「確かめたこと」）。この 2 つには自動の rollback がないので、`rollback` に `remarks: ""` を書く。PostgreSQL は空の文字列のコメントを削除として扱う（`src/backend/commands/comment.c:165-167`）。
- jOOQ のコード生成は、DB のコメントを既定で生成物の Javadoc と `getComment()` に写す（`jooq-codegen-3.21.1.xsd:3193-3211` の `comments`、`commentsOnTables`、`commentsOnColumns` の既定は `true`）。こちらの `backend/gradle/database.gradle:149-155` はこれを止めていない。生成物はコミットされている（`database.gradle:158-159`）ので、エージェントと開発者は生成物を読めば論理名を知る。
- 付け忘れは、既存の `SchemaInspectionTest` で止める。マイグレーション後の `pg_catalog` を読んで `SchemaConventions.check` で判定する仕組みがあり、規則を足す手順も決まっている（`docs/backend/architecture-tests.md:376-394`）。`CatalogSnapshot` の `RelationRow` と `ColumnRow` にコメントを足し、`SchemaTableConventions` に、業務の表と列のコメントが空なら違反にする規則を足す。`modulith` と `liquibase` のスキーマは、ほかの規則と同じく対象外にする（`architecture-tests.md:394`）。
- 論理名は `GLOSSARY.md` の用語にそろえる（`postgresql-naming.md:25` の辞書の規則と同じ考え）。一致の機械検査は、辞書と同じく今はしない（`postgresql-naming.md:26`）。

新しい道具は足さない。
tbls（v1.97.0）は DB のコメントから定義書を作り、`requireColumnComment` で付け忘れを止められるが、こちらにはコメントを読む別の利用者がいない。
コミットした jOOQ の生成物が、原文の「コードを正にして自動で出力する簡易データカタログ」（`metadata.md:44-104`）の役を果たし、付け忘れの検査は既存のスキーマ検査で足りる。

### 業務に要らない個人データの列を作らない

原文の「最初から持たないこと」（`data-masking.md:62`）は、分析に渡す項目についての原則である。
こちらは外に渡すデータには同じ原則を持つ（上の「同じ決定」）が、DB に保存する列についての規則がない（`docs/database/postgresql-logical-design.md` の節に個人データの扱いがない）。

今の表に個人データの列はない。
`tenancy` の `m_user_identity` は IdP の `issuer_url` と `subject_code` だけを持ち、氏名やメールアドレスを写していない（`014-create-tenancy-tables.yaml`）。
ADR-102 はアプリが標準の claim だけを読むと決めている（ADR-102:46）。
一方、#363 は招待とメールアドレスの一致で所属を作る（#363 の Q10）ので、メールアドレスを保存する列が入る。
規則を論理設計の docs に書き、#363 の実装がそれを読めるようにする。

- 個人データの列は、業務の処理に要るものだけを作る。
- IdP が持つ利用者の属性（氏名、メールアドレスなど）は、業務の処理に要るときだけ写す。表示のためだけに写さない。
- 個人データを含む表は、保持期限と同じ一覧に書く（`design-doc-sources-of-truth.md:41` の規則のまま）。

これは原文の原則を、こちらの保存に広げたものである（判断）。

## 取り込まないもの

- **データ分類（L1〜L4）と用途制約フラグ**（`data-classification.md`）：分類は、データ基盤の上で、どの部署がどこまで二次利用してよいかを提供側にお墨付きとして示す枠組みである（`data-classification.md:20-22`）。
  こちらには二次利用者がおらず、データを読める範囲はテナントと役割（ADR-101）とモジュールのロール（ADR-094）で決まる。
  学習不可などのフラグも、データを AI の学習に渡す経路がこちらにないため、読む人がいない。
- **列ごとの個人データの印**：原文はマスキングの対象の列をデータオーナーが指定し、カタログに表示するとする（`data-masking.md:83-90`）。
  こちらには、印を読んでマスクする処理も、表示するカタログもない。
  個人データかどうかは機械では判定できず、印の付け忘れも検査できない。
  表の単位の記録（`design-doc-sources-of-truth.md:41`）だけにする。
- **マスキングの基準と手法の表**（`data-masking.md` の 5W1H の表と手法の表）：データ基盤で他部署へ公開するためにマスクする手順である。
  こちらがデータを外に出す経路（ログ、イベント、連携ファイル）は、それぞれの規則で「渡さない」を選んでいる（上の「同じ決定」）。
  ログでの扱いは #321 が調べる。
- **データカタログの立ち上げと運用、メタデータの入力責任、利用状況の観測、データリネージ**（`metadata.md`、`metadata-operation.md`）：利用者がデータ基盤のデータを自分で探すための仕組みである。こちらの表を読むのは、こちらのモジュールだけである。
- **データ品質の SLA と Data Contract**（`data-quality.md`）：向こうの #214 と同じ理由で取り込まない。こちらにはデータ基盤も二次利用者もない。
- **データ基盤への連携とアーキテクチャ、マスタデータ管理**（`data-architecture.md`、`master-data.md`）：他システムやデータ基盤への連携は `docs/database/postgresql-data-integration.md` と `docs/integration/` が扱う。
- **組織変更と権限のライフサイクル**（`data-lifecycle.md`）：原文は IdP を正にしてデータ基盤のグループの所属を自動で連携するとする（`data-lifecycle.md:28`）。
  こちらは IdP の役割とグループを使わず、所属と役割をアプリの DB に持つと決めた（ADR-101:41-45、#316）。個別の承認、データオーナーの引き継ぎ、孤児データの検知は、データ基盤とデータカタログの運用である。
- **推進活動、推進体制と役割、データオーナー、費用負担、評価指標、技術トレンド**（`promotion.md`、`organization.md`、`data-owner.md`、`cost-sharing.md`、`value-and-evaluation.md`、`tech-trends.md`）：会社の組織と予算の決め方で、コードのテンプレートであるこちらに当てはまらない。
- **AI-Ready のデータガバナンス**（`ai-ready.md`）：業務データを生成 AI や RAG に渡す場面のガードレールである。こちらの業務機能は業務データを AI に渡さない。
- **原文の全文を置く形**：ADR-040 は原文を転載せず、当てはまるルールだけを書き直して取り込むと決めている（ADR-040:36-42）。向こうのように 18 の文書を置くと、ほとんどがこちらに当てはまらない規約として残る。

## ほかの Issue との関係

- **#558（バッチの docs）**：#558 も ADR-040 の Decision（:38-39）を直す。どちらかが先に merge したら、もう一方はその上で直す。
- **#363（tenant_id と RLS、招待）**：招待のメールアドレスの列は、取り込む「個人データの列」の規則に従う。#363 が足す表と列にも論理名のコメントが要る。作業の Issue が先に merge したら、#363 は検査に合わせてコメントを書く。後なら、作業の Issue が #363 の表にもコメントを足す。
- **#321（ロガーの文脈とマスクの方式）**：ログの個人データとマスクは #321 が扱い、この調査は扱わない。
- **#312、#560（イベントのスキーマ）**：向こうの #214 の Q2 と同じ決定で、変えない。

## 限界

- 原文の章は読んだが、向こうの元にした原文の版と、こちらの ADR-040 が固定した版（`e309a6d`）の差は、取り込む 2 つの箇所だけを確かめた（`data_governance_guidelines.md:572`、`:635-637`）。
- jOOQ が DB のコメントを生成物に写すことは、XSD の既定値と、今の生成物にコメントの引数（`DSL.comment("")`、`createField(..., "")`）があることで確かめた。コメントを付けた DB からコード生成を流してはいない。
- 個人データかどうかの判断は、検査できない。規則は docs とレビューで守る。

## 確かめたこと

- Liquibase 5.0.4（こちらの版で、最新のリリースと同じ）を offline の PostgreSQL で動かし、`setTableRemarks` と `setColumnRemarks` の changeset の `update-sql` が `COMMENT ON TABLE ordering.t_order IS '注文';` と `COMMENT ON COLUMN ordering.t_order.ordered_at IS '注文日時';` を出すことを確かめた。
- 同じ changeset から `rollback` を除くと、`future-rollback-sql` が `No inverse to liquibase.change.core.SetColumnRemarksChange created` で失敗した。`rollback` に `remarks: ""` を書くと `COMMENT ON COLUMN ordering.t_order.ordered_at IS '';` を出した。
- jOOQ 3.21.9 の `jooq-meta` の `jooq-codegen-3.21.1.xsd` で、`generate` の `comments`、`commentsOnTables`、`commentsOnColumns` の既定が `true` である。別の `comments` の要素（:1259、設定でコメントを足す機能）は商用版だけだが、DB のコメントを写すのには要らない。
- jOOQ の最新は 3.21.10（2026-11-17）で、リリースノートにコメントの生成に関わる変更はない。
- こちらの生成物 `TOrder.java:76-114` の `createField` の最後の文字列の引数と `:163` の `DSL.comment("")` が、今は空である。
- 向こうの `packages/db/migrations/` に `COMMENT ON` がない。

## 出典

- フューチャー株式会社「データガバナンス設計ガイドライン」（[原文](https://future-architect.github.io/arch-guidelines/documents/forDataGovernance/data_governance_guidelines.html)、commit `e309a6d`、CC BY 4.0）
- [Liquibase setColumnRemarks](https://docs.liquibase.com/change-types/set-column-remarks.html)、[setTableRemarks](https://docs.liquibase.com/change-types/set-table-remarks.html)
- [jOOQ のコード生成の comments](https://www.jooq.org/doc/latest/manual/code-generation/codegen-advanced/codegen-config-generate/codegen-generate-comments/)、[jOOQ 3.21.10 のリリースノート](https://github.com/jOOQ/jOOQ/releases/tag/version-3.21.10)
- [PostgreSQL COMMENT](https://www.postgresql.org/docs/current/sql-comment.html)、[comment.c](https://github.com/postgres/postgres/blob/master/src/backend/commands/comment.c)
- 向こう：`docs/data-governance/`、`docs/index.md`、`docs/db/convention-check.md`、`packages/db/migrations/`、yokozi-jp/agent-software-factory#19、yokozi-jp/agent-software-factory#40、yokozi-jp/agent-software-factory#214
- こちら：ADR-040、ADR-094、ADR-101、ADR-102、`docs/database/postgresql-naming.md`、`docs/database/postgresql-data-types.md`、`docs/database/postgresql-logical-design.md`、`docs/database/postgresql-data-integration.md`、`docs/database/migrations.md`、`docs/writing/design-doc-sources-of-truth.md`、`docs/backend/architecture-tests.md`、`docs/observability/conventions.md`、`docs/integration/async-message-security.md`、`docs/integration/interface-file-security.md`、`backend/gradle/database.gradle`
