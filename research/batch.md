# バッチの方式と、バッチの設計の docs（#314 の調査）

この文書は #314 の調査の記録で、merge しない。
結論は #314 のコメントと同じで、作業は #558 で行う。
向こうは `yokozi-jp/agent-software-factory`（`origin/main`、commit `29ba897`）、こちらは `yokozi-jp/spring-modulith-ai-dlc`（main、commit `602d693`）である。

## 結論

一部採用とする。
向こうの ADR 0042 の方針のうち、次を取り込む。

- 毎日起動して、前回の続きから追いつく。
- 再開の単位と状態を業務の行に持つ。
- 二重の起動を業務のキーで止める。
- オンラインとの排他の要否を、書く表とその行の状態で決める。
- 試験の観点。

バッチの設計の docs は、向こうのように原文をほぼそのまま置かず、ADR-040 の方式で書き直して、新しい領域 `docs/batch/` に置く。
ADR-040 はバッチを取り込まない原文に挙げている（ADR-040:39）ので、ADR-040 を改める。
Step Functions、Map、redrive、ワークフローエンジンとバッチコントローラの選び方、業務日付の切り替えのパターン、「常にバッチで上書き」の形は取り込まない。
作業の Issue を 1 つ起こした（#558）。

## 取り込むもの

- **バッチの設計の docs（新しい領域 `docs/batch/`）**：こちらは、バッチを前提にした規則をすでに持つ。
  - ADR-041 は、バッチのタスクを Fargate で動かすと決めている（ADR-041:27）。
  - ADR-055 は、バッチのトランザクションだけ `statement_timeout` を上げてよいとしている（ADR-055:58）。
  - `postgresql-concurrency-control.md:131-132` は、バッチの `NOWAIT` と `SKIP LOCKED` を書いている。
  - `performance-targets.md:74` は、バッチウィンドウから目標を決めるとしている。
  - ADR-051 は、バッチの入口を作るときに役割と pointcut を決め直すとしている（ADR-051:96）。

  それなのに、ジョブの入口、追いつき、冪等性と再開、オンラインとの排他、試験を定める文書がない。
  ADR-040 がバッチを除いた理由は、ADR にも #86 の本文にも書かれていない（#86 は「対象外です」とだけ書く）。
  同じ Future のガイドラインの I/F、非同期、PostgreSQL、ログの章は取り込んであり、バッチの章だけが欠けている。
  置き場所は、`docs/integration/` ではなく新しい領域にする。
  `docs/integration/index.md` はすでに 29 行あり、バッチのジョブには連携を伴わないもの（集計、締め、保持期限の削除）もあるためである。
  手順は `docs/knowledge/documentation-authoring.md:140-146` に従い、steering `batch.md` と `docs-navigation` の行を足す。
- **毎日起動して、前回の続きから追いつく**（ADR 0042:14、原文 `scheduled-jobs.md:30-32`、`cycle-jobs.md:37-38`）：期間で区切るジョブ（月次の締め、日次の集計）は、業務の表に「最後に処理した期間」を持つ。
  起動のたびに、その次の期間から「起動した時刻の前の期間」までを古い順に処理する。
  処理する期間がなければ、0 件として終了のログを出して終える。
  前回が失敗しても次回がそのまま続きを流し、手での再実行を前提にしない。
  手で起動するときは「どの期間まで処理するか」を渡す。
  この形は、こちらの規約と合う。
  `interface-job-ordering.md:18` は「前回が異常終了しても、次回の定時起動でそのまま実行できるように設計する」としている。
  `postgresql-temporal-data.md:32` は、複雑な定時処理がなければ業務日付を管理しなくてよいとしている。
  追いつく形なら、業務の表が「どの期間を処理するか」を決めるので、業務日付管理テーブルがなくてもジョブが動く。
  業務日付管理テーブルを持つかは、これまでどおり `postgresql-temporal-data.md:18-32` で決める。
  期間の境界は業務タイムゾーンで求め（ADR-046:36-38）、半開区間で書く（`postgresql-data-types.md:24-25`）。
  どちらも既存の規約なので、新しい docs からはリンクだけにする。
- **再開の単位と状態を業務の行に持つ**（ADR 0042:22）：再開の単位（例：テナントと期間の組）ごとに、1 つのトランザクションで確定する。
  業務の行に状態（確定済み、配信済みなど）を持ち、済んだ手順を飛ばす。
  ジョブの実行の記録の表は別に置かない。
  こちらでは、ジョブの入口が単位ごとに 1 つの CommandHandler を呼ぶ形にする。
  Listener が 1 つの CommandHandler だけを呼ぶ形（`ClassRoleArchTest.java:217` の `listenersExposeOnlyOnAndCallOneCommandHandler`）と同じである。
  `*_pgm_cd` は CommandHandler の `handle` の呼び出しで束縛される（ADR-051:46）ので、この形なら ADR-051 の pointcut を変えずに済む。
  ADR-051:96 が心配しているのは、CommandHandler を通らずに書く入口である。
  入口は、単位ごとにスパンを始め、テナントを設定してから CommandHandler を呼ぶ。
  `*_tx_id` は現在のスパンの trace ID を要し、trace がなければ登録が失敗するためである（`postgresql-common-columns.md:39`）。
  テナントの設定は、#363 の Q7 が Listener の入口でイベントから行うと決めた。
  ジョブの入口でも、同じ点を決める必要がある。
  1 つのテナントの失敗が、ほかのテナントを止めない点は向こうと同じにする（ADR 0042:17）。
- **二重の起動を業務のキーで止め、ShedLock を正しさの根拠にしない**（ADR 0042:22 の `(tenant_id, closing_month)` の一意制約）：同じ単位のジョブが 2 本同時に走っても、単位の一意制約で負けた側が、確定済みとして続きを流すか、何もせずに終わるようにする。
  重複の判定は、#551 と同じく DB が直列にする操作で行う。
  ShedLock は、インスタンスの数だけ同じ仕事をしないために使い、正しさは業務のキーで守る。
  ShedLock の README は、`lockAtMostFor` を超えた実行では複数のプロセスがロックを持ちうると書いている（下の「確かめたこと」）。
  ADR-079 もこの前提に立つ。
  上限を超えて二つ目のインスタンスが動いたときは、`markResubmitted` の原子的な更新で二重の投入を止めている（ADR-079:176-177）。
  #542 は、同じ条件の `DELETE` は何度流しても結果が変わらないので ShedLock を掛けないと決めた。
  docs には、この使い分けがまだない。
  また、`interface-job-ordering.md:17` は多重起動の防止を「ワークフローエンジンの多重起動防止の機能で行い、アプリケーションに独自の排他を実装しない」としている。
  こちらはワークフローエンジンを持たず、ADR-079 はアプリの中の ShedLock で排他している。
  この行を、ワークフローエンジンがない構成に合わせて直す。
- **オンラインとの排他の要否を、書く表とその行の状態で決める**（ADR 0042:19、原文 `exclusive-control.md:36-41`）：ジョブとオンラインが、同じ時間帯に同じ表の同じ列を書くかで要否を分ける。
  ジョブが読む行がオンラインでもう変わらない状態にあり、ジョブが自分のモジュールの表とイベントだけを書くなら、ロックも `REPEATABLE READ` も使わない。
  向こうの締めは、この条件で排他を省いた。
  集約を更新するなら、読んだ版と行の版を比べる書き方（`postgresql-concurrency-control.md:76`）を使い、競合した単位は失敗として次の回に回す。
  追いつく形なので、次の回が続きを流す。
- **入口の形を 2 つに分ける**：
  - 短い保守の処理（ADR-079 の再投入、#542 の archive の削除）は、アプリの中の `@Scheduled` で動かす。
  - 業務のバッチ（締め、集計、ファイルの配信と集信）は、同じイメージを 1 回だけ起動するジョブにする。
    Web のサーバーを動かさず、パラメータを環境変数で受け取って起動時に検査し、結果を終了コードで返す。
    形は、すでにある `EventPublicationResubmitOnce`（ADR-079:133-138、`EventPublicationResubmissionConfig.java:128-136`）と同じにする。

  向こうも、バッチを HTTP のアプリにせず、環境変数を検査する CLI のタスクにした（ADR 0042:24-30）。
  原文の `batch-execution-model.md:41-43` は分単位のバッチをスタンドアローンタスクにするよう勧め、Web API のサーバーの上で動かすとリソースを共有するとしている（同 :33）。
  こちらの `authentication-and-session.md:134` も、アプリの中のバッチは自分の Web API を呼ばず、アプリケーション層を直接呼ぶとしている。
  ジョブは起動元（EventBridge Scheduler など）とは独立に作る。
  どの起動元から何度起動されても、上の追いつきと一意制約で正しく終わるようにする。
  入口の形、役割、スパンとテナントは、新しい ADR に残す（#558）。
- **試験の観点**（ADR 0042:34-36、原文 `test.md:24-32`）：
  - 純粋な関数の単体テストで、処理する期間の範囲と境界の時刻を確かめる。
  - 結合テストでは、ジョブの入口をスケジューラを通さずに呼び、`Clock` を進めながら次を確かめる。
    2 回流して結果が同じこと、0 件、日回し（毎日起動して 3 か月続ける）、期間を飛ばした後の追いつき、途中で落とした後の再開、2 つのテナントの分離、同時に 2 本。
  - 境界の前後の確認は、向こうの 1 ms ではなく 1 マイクロ秒にする。こちらは絶対時刻をマイクロ秒精度で持つ（`docs/datetime/timezone-conventions.md` の「精度」）。
  - 未起動と遅延の検知（原文 `test.md:30-31`）は、「最後に処理した期間」が期待より古いことを監視する観点として書く。
- **原文の残りの規則のうち、こちらに当てはまるもの**（書き直して取り込む）：
  - 起動時刻を業務時間帯にし、門限とリカバリの余裕で決め、ほかのジョブと重ねない（`scheduled-jobs.md:24-29`、`:36-38`）。
  - 0 件の挙動を決める（`scheduled-jobs.md:33-35`）。0 件の終了ログは `log-output-points.md:64` にあるので、リンクにする。
  - 冪等の作り方として、状態の列で未処理だけを選ぶか、MERGE に当たる書き方で書く（`job-design.md:30-37`）。
  - 入力チェックの濃淡と、自分の出力をアプリで検証しないこと（`job-design.md:41-62`）。
  - プログラムの中で DB アクセスを並列化しないこと（`job-design.md:103`）。トランザクションが分かれ、ジョブの成否と一致しなくなるためである。
  - ジョブ同士に依存ができたら、システムタイムチャートを作ること（`documentation.md:50-51`）。

## 取り込まないもの

- **Step Functions、Map、`waitForTaskToken`、redrive、Scheduler との比較**（ADR 0042:16-17、:41-44）：こちらには AWS の構成がない。
  ADR-045 は、AWS の規約を、必要になった時点で新しい領域として書き直すと決めた（ADR-045:50）。
  ここで Step Functions だけを決めると、AWS の構成を ADR-045 の書き直しと別に決めることになり、ADR-045 と食い違う。
  向こうが Step Functions を挟む理由は 3 つある（ADR 0042:41-44）。
  - 1 つ目のタスクの再試行は、追いつく形なら次の起動が続きを流す。向こうも、`Retry` の後は翌日の起動に任せている（ADR 0042:22）。
  - 2 つ目の失敗の検知は、ジョブが終了コードを返すので、起動元を決めるときに選べる。
  - 3 つ目の「ジョブ同士の依存が普通にある」は、こちらに当たらない。こちらのジョブは再投入（`EventPublicationResubmitter.java:170` の `@Scheduled` だけ）と #542 の archive の削除で、互いに依存しない。原文も、依存がなければ Scheduler から直接起動するよう勧めている（`workflow-engine.md:116-122`）。

  ジョブの作りを起動元から独立させるので、AWS の規約の書き直しで Step Functions を選んでも、ジョブは変わらない。
- **ワークフローエンジンの選定と占有、ワークフローの命名とネスト、バッチコントローラ、非同期タスクの終了判定、バッチ実行モデル**（原文 `workflow-engine.md`、`workflow-engine-adoption.md`、`workflow-design.md`、`batch-controller.md`、`async-tasks.md`、`batch-execution-model.md`）：どれもワークフローエンジンを前提にし、こちらには合わない。
  実行モデルのうち Lambda は、ADR-041 が使わないと決めた。
  向こう自身も、これらの原文の誤りと古い記述を直している（`about.md:31-36`）。
  ADR-045 が AWS の章を古いとして消したのと同じ問題である。
- **業務日付の切り替えのパターンとタイムゾーンの分離**（原文 `business-date-patterns.md`、`business-date-timezone.md`）：こちらは、業務タイムゾーンをシステムで 1 つの設定値にした（ADR-046:38）。
  タイムゾーンごとに業務日付を分ける形は、ADR-046 と合わない。
  オンライン用とバッチ用を分ける業務日付管理テーブルの規則は、`postgresql-temporal-data.md:26` にすでにある。
  機能グループごとの切り替えは、原文自身が「複雑になりすぎるとアプリケーション品質に影響しかねない」としている（`business-date-timezone.md:22`）。
- **「常にバッチで上書き」の形**（原文 `exclusive-control.md:63-69`）：オンラインが `SELECT ... FOR UPDATE NOWAIT` で悲観的ロックを取ることを前提にする。
  こちらのオンラインは、読んだ版と行の版を比べる楽観的ロックである（ADR-054、`postgresql-concurrency-control.md:76`）。
  HTTP の要求と応答をまたいでロックを保てないので、画面で悲観的ロックを取らない（`postgresql-concurrency-control.md:137-138`）。
  「部分更新と NG リスト」の `SKIP LOCKED` は、すでに `postgresql-concurrency-control.md:132` にある。
- **バッチのリリース**（原文 `release.md`）：デプロイの後方互換は、expand-and-contract の規約（`postgresql-online-schema-change.md:18`）に含まれる。
  ジョブを下流から順に出す規則は、ワークフローでつないだジョブのためのもので、上でワークフローを取り込まないので要らない。
- **Spring Batch**（向こうにはなく、こちらで比べた案）：向こうは、実行の記録の表を置かないと決めた（ADR 0042:22）。
  Spring Batch で再開すると、再開の状態を JobRepository の表と業務の行の 2 か所に持つことになる。
  上の「再開の単位と状態を業務の行に持つ」と合わない。
- **照合の結合落ちの検知**（原文 `job-design.md:59`）：こちらのモジュールは、ほかのモジュールの表を読まない（`DatabaseConventionsArchTest.java:92` の `persistenceAdaptersUseOnlyOwnGeneratedSchema`）。
  表の結合で行が欠ける場面がない。
  向こうも、#354 の決定 7 では結合落ちを検知するとしたが、ADR 0046 の後に ADR 0042:21 でやめている。
  件数を終了のログに出すことは、`log-output-points.md:61` にある。
- **Effect、Hono、zod、`ManagedRuntime`、`closing_module` に `auth.organization` の列を読ませる例外**（ADR 0042:18、:24-30）：向こうの技術と認証（Better Auth）に固有である。
  こちらはテナントをアプリの DB に持つ（#316、#363）ので、ジョブはテナントの一覧を `tenancy` の `m_tenant` から読む。
- **原文の転載**：向こうは、原文を分割して frontmatter を足しただけで、本文をほぼ変えていない（`about.md:23-36`）。
  こちらは ADR-040 のとおり書き直し、既存の規約と同じ内容はリンクにする（ADR-040:40-43）。

## コメントへの回答

- **yokozi-jp/agent-software-factory#349（Step Functions と ECS RunTask のワークフローをローカルと CI で試す方法）**：TestState API、CDK assertions、`asl-validator` などの試験の手段は取り込まない。
  こちらには、試すワークフローの定義がない。
  取り込むのは、#349 の次の 2 点で、どちらも上の「取り込むもの」に入っている。
  - ワークフローを通さず、ジョブを直接起動して試す（試験の観点）。
  - redrive は失敗した段を新しいタスクとして起動し直すので、ジョブの冪等性が前提になる（二重の起動を業務のキーで止める）。
- **yokozi-jp/agent-software-factory#354（業務日付、照合、再実行、途中からの再開、オンラインとの排他）**：
  - 業務日付：追いつく形を取り込む（決定 1）。業務日付管理テーブルの規則は、こちらの `postgresql-temporal-data.md` のままにする。
  - 照合：件数を終了のログに出すことは既存の規約にある。結合落ちの検知は取り込まない（決定 7 は ADR 0042:21 で取り消されている）。
  - 再実行と途中からの再開：取り込む（決定 8）。Step Functions の `Retry` と redrive は取り込まない。
  - オンラインとの排他：要否の決め方を取り込む（決定 5）。
  - 試験：取り込む（決定 11）。Step Functions を試験で通さないことも同じにする。
  - 入口：HTTP のアプリにしないことを取り込む（決定 10）。こちらの形は `EventPublicationResubmitOnce` にそろえる。

## ほかの Issue との関係

- **#363、#377（テナントと RLS）**：ジョブの入口は、単位ごとにテナントを設定してから CommandHandler を呼ぶ。
  #363 の Q7 が Listener の入口に決めた形と、#558 でそろえる。
  #558 は docs と ADR と ArchUnit の規則だけなので、#377 に依存しない。
- **#542（archive を保持期限で消す）**：「短い保守の処理は `@Scheduled`」の例で、ShedLock を掛けない判断は「正しさは業務のキーで守る」と合う。食い違わない。
- **#551（リスナーに同じイベントを 2 回届ける全件のテスト）**：重複の判定を DB が直列にする操作で行う規則を、ジョブの単位の一意制約にも使う。
  ジョブの「同時に 2 本」の試験は、#551 の同時の 2 回と同じ考え方である。
- **#367（テナントをまたいで操作する運用者の役割）**：ジョブはテナントを 1 つずつ設定して回すので、テナントをまたぐ人の役割を要しない。#367 の結論を待たない。
- **ADR の変更**：ADR-040 の Decision を書き直す（Proposed なので `docs/adr/conventions.md:66` に従い直接直す）。
  新しい ADR を起こし、ADR-051:96 の Negative の行をその ADR へのリンクに直す。
  ADR-045 は変えない。起動元を決めるのは、ADR-045 の AWS の規約の書き直しのときである。

## 限界

- こちらには業務のバッチがまだない。
  規則は、向こうの締めの ADR と原文から書いたもので、こちらのジョブで動かしていない。
  向こうも、ジョブの本体と Step Functions の CDK を書いていない（ADR 0042:73）。
- 起動元と、起動しなかったことを検知する仕組みは決めていない。
  #558 は、「最後に処理した期間」が古いことを監視する観点とメトリクスを決めるが、警報をどこから出すかは起動元とともに決める。
- 1 回だけ起動するジョブの終了コードを、ECS の RunTask と Kubernetes の Job が成否として扱うことは、動かして確かめていない。ADR-079:170 の記述に頼っている。
- Spring Batch を採らない理由は、再開の状態の持ち方の比較で、動かして比べていない。

## 確かめたこと

使い捨ての `postgres:18`（18.6）のコンテナで、次を確かめた。

- スキーマの `USAGE` と表の `SELECT` だけを与えたロールで、`SELECT ... FOR UPDATE` は `permission denied for table review` で失敗し、同じロールの `SELECT` は通る。ADR 0042:57 の「`FOR UPDATE` には `UPDATE` 権限も要る」と合う。
- `timestamp '2026-12-01 00:00' at time zone 'Asia/Tokyo'` は `2026-11-30 15:00:00+00` になる。
  この境界ちょうどの行は、半開区間（`>=` と `<`）では 11 月に入らない。
  `BETWEEN` では 11 月と 12 月の両方に入る。

ShedLock の README（7.11.0、こちらの `backend/build.gradle:108` の版）で、次を確かめた。

- 「If the task takes longer than `lockAtMostFor`, it may be executed again and the results will be unpredictable (more processes will hold the lock).」と書いている。
- ロックが取られているとき、同じロックのほかのタスクは待たずに飛ばされる。

こちらのリポジトリ（main、commit `602d693`）で、次を確かめた。

- `backend/src/main` の `@Scheduled` は `EventPublicationResubmitter.java:170` だけで、ShedLock の `LockingTaskExecutor` を使うのは、再投入の定期のジョブと手での 1 回の再投入だけである。
- 手での 1 回の再投入は、`event-publication.resubmit-once.target` があるときだけ動き、終了コードで JVM を終える（`EventPublicationResubmissionConfig.java:128-136`、`:172-173`）。
- ADR-040:39 はバッチを取り込まない原文に挙げ、#86 の本文も「対象外です」とだけ書く。
- `docs/` にバッチの領域はなく、`docs/` の「バッチ」は上に挙げた規約の部分的な記述だけである。
- `interface-job-ordering.md:17` は多重起動の防止をワークフローエンジンに任せ、アプリでの排他を禁じている。

向こうのリポジトリ（`origin/main`、commit `29ba897`）で、次を確かめた。

- 締めのジョブのコードはない。`packages/closing/src/index.ts` は「The module has no code yet」と書き、DB の型とトランザクションだけを export する。
- `docs/batch/` は 18 文書で、原文の本文をほぼ変えずに分割している（`about.md:23-36`）。業務日付の本文は変えず、締めの ADR だけが違う判断をすると書いている（`about.md:34`）。
- #354 の決定 7（結合落ちの検知）は、ADR 0042:21 で、審査だけを読み結合落ちを検知しない形に変わっている。

## 作業の Issue

- #558

## 出典

- [ShedLock README（7.11.0）](https://github.com/lukas-krecan/ShedLock/blob/shedlock-parent-7.11.0/README.md)
- [PostgreSQL SELECT（The Locking Clause）](https://www.postgresql.org/docs/current/sql-select.html#SQL-FOR-UPDATE-SHARE)
- [Don't Do This - PostgreSQL wiki（BETWEEN）](https://wiki.postgresql.org/wiki/Don't_Do_This)
- [フューチャー株式会社 バッチ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forBatch/batch_guidelines.html)（CC BY 4.0）
- 向こう：`docs/adr/0042-closing-batch-catches-up-daily-without-locks.md`、`docs/batch/` の 18 文書、`docs/interface/overtaking-control.md`、`docs/logging/batch.md`、`packages/closing/src/index.ts`、yokozi-jp/agent-software-factory#349、yokozi-jp/agent-software-factory#354
- こちら：ADR-040、ADR-041、ADR-045、ADR-046、ADR-051、ADR-054、ADR-055、ADR-079、`docs/integration/index.md`、`docs/integration/interface-job-ordering.md`、`docs/integration/interface-file-layout.md`、`docs/database/postgresql-temporal-data.md`、`docs/database/postgresql-data-types.md`、`docs/database/postgresql-concurrency-control.md`、`docs/database/postgresql-common-columns.md`、`docs/database/postgresql-online-schema-change.md`、`docs/observability/log-output-points.md`、`docs/web-api/authentication-and-session.md`、`docs/performance-test/performance-targets.md`、`docs/knowledge/documentation-authoring.md`、`docs/adr/conventions.md`、`EventPublicationResubmitter.java`、`EventPublicationResubmitOnce.java`、`EventPublicationResubmissionConfig.java`、`ClassRoleArchTest.java`、`DatabaseConventionsArchTest.java`、#86、#363、#542、#551
