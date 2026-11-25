---
name: formal-verify-event-resubmission
description: 失敗したイベント出版の再投入を、時間つきの TLA+ のモデルで、2 台のインスタンスと停止を含めて全順序検査する。再投入のジョブ、ロック、方針、ロックの中で古い出版を FAILED に戻す処理、event-publication.resubmission と spring.modulith.events の設定、Spring Modulith か ShedLock の版を変えるときと、リスナーの二重実行や再投入の漏れを疑うときに使う。
---

# イベント出版の再投入の形式検証

一つのイベント出版について、2 台のインスタンスの再投入のジョブ、ShedLock のロック、ロックの中で古い出版を `FAILED` に戻す処理、リスナーの配信、インスタンスの停止と再起動を、離散の時間の上ですべての順序で検査する。
道具は TLA+ の TLC である。
問いが「時間の条件と複数の台の実行がどう重なっても約束が守られるか」であり、テストではロックの期限切れや実行の遅れの重なりを作れないためである。
検査の実行、結果の読み方、切り分け、モデルの直し方、同期コミットの進め方は `docs/agents/formal-verification.md` に従う。

## 実行する

```bash
tooling/formal-verify/check.sh .agents/skills/formal-verify-event-resubmission
tooling/formal-verify/mutants.py .agents/skills/formal-verify-event-resubmission
```

## 約束（不変条件）

| 名前                               | 約束                                                                                                                                                                            | 破れたときの害                                     |
| ---------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------- |
| `OneDeliveryAtATime`               | 一つの出版のリスナーが、同時に二つ動かない（待ちの列にあるものも含む）。                                                                                                        | 冪等でない副作用が二重に起きる。                   |
| `ResubmissionsCapped`              | ジョブが一つの出版を再投入する回数は、リスナーの方針の `abandon-after-attempts`（指定がなければ全体の値）まで。1 なら再投入しない。                                             | 回復しない出版を際限なく流し、上限の警報も遅れる。 |
| `NoResubmissionWhenAbandonOnFirst` | 方針の `abandon-after-attempts` が 1 のリスナーの出版を、停止で配信が処理中のまま残っても、ジョブが再投入しない。                                                               | 冪等キーを持たない外部システムを二重に呼ぶ。       |
| `OneLockedRun`                     | ロックの中の処理を、同時に 2 台が実行しない。                                                                                                                                   | 古い一覧で同じ出版を重ねて再投入する。             |
| `InFlightNeverMarkedStale`         | 古い出版を `FAILED` に戻す処理は、待ちの列にあるか実行中の配信の出版を `FAILED` に戻さない（ロックと、起動時の検査 `isInFlightStalenessLongerThanTwiceLockAtMostFor` の約束）。 | 処理中の出版がまた再投入の対象になる。             |

## 設定と期待する結果

時間の単位は任意で、定数は小さな整数にしてある。
結果が示すのは「実行が `lock-at-most-for` の半分より長い」のような値の大小関係であり、本番の分や秒の値ではない。
どの設定も、`in-flight-staleness` を起動時の検査が許す最小（`lock-at-most-for` の 2 倍 + 1）にしている。
期待する結果の括弧の中は、`TypeOK` のほかに検査する不変条件である。

| 設定                                  | 内容                                                                                                                                                                                     | 期待する結果                                                                    |
| ------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| `documented.cfg`                      | 起動時の検査を通る設定と、`application.yaml` の注記が置く前提（リスナーの処理が `wait-age` に収まる、空きの待ちが `wait-age` より短い、実行が `lock-at-most-for` より短い）。停止は 1 回 | pass（全不変条件）                                                              |
| `run-overruns-lock.cfg`               | `documented.cfg` で、実行が `lock-at-most-for` より長引き、2 台が同時に動く                                                                                                              | pass（`OneDeliveryAtATime`、`ResubmissionsCapped`。時間の予算が守る）           |
| `run-overruns-lock-stale-mark.cfg`    | `run-overruns-lock.cfg` と同じ値                                                                                                                                                         | `InFlightNeverMarkedStale` が破れる（既知の発見 1）                             |
| `in-flight-fast-runs.cfg`             | 停止なし、各回の実行が時間の予算（`lock-at-most-for` の半分）のうちに出版へ届く                                                                                                          | pass（`InFlightNeverMarkedStale`）                                              |
| `in-flight-slow-runs.cfg`             | 停止なし、実行が時間の予算を過ぎてから出版へ届くことがあり、再投入が何回も遅れる                                                                                                         | pass（`InFlightNeverMarkedStale`）                                              |
| `listener-up-to-lock.cfg`             | 1 回の配信が `lock-at-most-for` まで続き、`wait-age` より長い。停止は 1 回                                                                                                               | pass（全不変条件）                                                              |
| `run-overruns-lock-long-listener.cfg` | `listener-up-to-lock.cfg` で、実行が `lock-at-most-for` より長引く                                                                                                                       | `OneDeliveryAtATime` が破れる（既知の発見 2）                                   |
| `policy-abandon-on-first.cfg`         | `documented.cfg` で、リスナーの方針の `abandon-after-attempts` を 1 にする。停止は 1 回で、最初の配信が処理中のまま残りうる                                                              | pass（全不変条件と `NoResubmissionWhenAbandonOnFirst`）                         |
| `delivery-up-to-twice-lock.cfg`       | 停止なし、空きの待ちとリスナーの処理が、それぞれ `lock-at-most-for` まで続く（最初の配信の待ちは `wait-age` 未満）                                                                       | pass（全不変条件。起動時の検査が 1 倍なら `InFlightNeverMarkedStale` が破れる） |

## 切り分けの手がかり

- 時間を進める `Tick` を止める義務（`MustActNow`）が、コードの定期の実行と期限に合っているかを見る。
- `StaleFind` と `StaleMark` の間に行の状態が変わった経路は、その回が `lock-at-most-for` を超えて長引き、ロックが切れた経路であり、既知の発見 1 と同じ原因である。
  実行が `lock-at-most-for` より短い設定（`RunMax <= LockAtMostFor`）でこの経路が出たら、`RunStart` のロックの条件を疑う。
- 停止のない設定では、出版の行が古くなる前に配信が終わるため、古い出版を `FAILED` に戻す処理は何も印を付けない。
  そこで `InFlightNeverMarkedStale` が破れたら、配信の待ちか処理の上限（`QueueMax`、`ListenerMax`）か `in-flight-staleness` の測り方を疑う。

## 変更がこの検証に関わるか

次のコマンドが差分を出せば関わる。

```bash
skill=.agents/skills/formal-verify-event-resubmission/SKILL.md
git diff --stat "$(sed -n 's/^同期コミット：`\([0-9a-f]*\)`.*/\1/p' "$skill")" -- \
  backend/src/main/java/com/example/demo/shared/eventresubmission/EventPublicationResubmitter.java \
  backend/src/main/java/com/example/demo/shared/eventresubmission/FailedPublicationSweep.java \
  backend/src/main/java/com/example/demo/shared/eventresubmission/EventPublicationResubmissionConfig.java \
  backend/src/main/java/com/example/demo/shared/eventresubmission/ResubmissionPolicies.java \
  backend/src/test/java/com/example/demo/shared/eventresubmission/ResubmissionOptionsLearningTest.java \
  backend/src/main/resources/application.yaml \
  backend/build.gradle
```

`application.yaml` は `spring.modulith.events`（`completion-mode`、`staleness`、`resubmission`）、`spring.task.execution`、`spring.task.scheduling`、`spring.threads.virtual`、`event-publication.resubmission` の節だけ、`build.gradle` は `springModulithVersion`、ShedLock、Spring Boot の版だけが関わる。
`spring.modulith.events.staleness` の閾値を書くと起動に失敗する（`EventPublicationResubmissionConfig.StalenessMonitorProperties`）。
この検査を外して閾値を書くと、組み込みの Staleness Monitor がロックの外で動き、モデルにない経路ができる。
`spring.threads.virtual.enabled` のとき Spring Boot が使う `SimpleAsyncTaskScheduler` は、fixed delay のタスクを一つのスレッドで順に動かすため、同じインスタンスの再投入のジョブの回は重ならない。
スケジューラを変えて回が重なるようになったら、モデルの `ThreadFree` を直す。

## 対応表

モデルは `model/EventResubmission.tla` にある。
各アクションはコードの一つの SQL の文か、メモリの中の一つの判断である。

| アクション    | コード                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| ------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `RunStart`    | スケジューラのスレッドが空くと、`EventPublicationResubmitter.resubmitOnce` が `now` を取り、`FailedPublicationSweep.tryOnce` の `executeWithLock` → ShedLock の `JooqStorageAccessor.updateRecord`（`lock_until <= now` の UPDATE、DB の時刻）                                                                                                                                                                                                                                                                                                    |
| `StaleFind`   | ロックの中で `FailedPublicationSweep` が呼ぶ `DefaultEventPublicationRegistry.markStalePublicationsFailed(Properties.staleness())` → `markFailed(Status, Staleness)` の `findByStatus` と `getLastPublicationDate()`（`last_resubmission_date`）の絞り込み                                                                                                                                                                                                                                                                                        |
| `StaleMark`   | 同じメソッドが読んだ一覧の各行に呼ぶ `JdbcEventPublicationRepositoryV2.markFailed(id)`（`completion_date IS NULL AND status != 'FAILED'` の UPDATE。状態と日時を確かめ直さない）                                                                                                                                                                                                                                                                                                                                                                  |
| `RunRead`     | 同じロックの中で、`FailedEventPublications.resubmit` → `findFailedPublications`（`status = 'FAILED'` の SELECT）。一覧はその回の間古いまま使う                                                                                                                                                                                                                                                                                                                                                                                                    |
| `RunFinish`   | `withFilter` の述語（`EventPublicationResubmitter.dueAt` の時間の予算と `ResubmissionPolicies.isDue`、`FailedPublicationSweep` の `max-per-run`）、`markResubmitted`（`status != 'RESUBMITTED'` の UPDATE）と配信、ShedLock の `unlock`（`max(locked_at + lockAtLeastFor, now)`、`locked_by` が自分のとき）                                                                                                                                                                                                                                       |
| `ExecStart`   | `CompletionRegisteringMethodInterceptor` → `markProcessing`。配信は `spring.task.execution` の executor の空きを待ってから始まる                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `ExecSucceed` | `markCompleted`（archive への写しと削除）                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `ExecFail`    | `markFailed` → `markFailedOrAbandoned`。`AbandonPolicies` が、`ResubmissionPolicies.shouldAbandon`（`AbandonPolicy` の bean）で、渡された出版の `getCompletionAttempts()`（配信の `seen`。最初の配信は 1、再投入は `RunRead` で読んだ値）をリスナーの方針の `abandon-after-attempts`（`PolicyAbandon`。指定がなければ全体の値で、既定の `ResubmissionProperties.shouldAbandon` と同じ判断）と比べ、それ以上なら `markAbandoned`（archive へ `ABANDONED` で写して削除。状態を確かめない）、未満なら `markFailed`（`status != 'FAILED'` の UPDATE） |
| `Crash`       | インスタンスの停止。ロックの行は `lock_until` まで残り、そのインスタンスの回（古い出版を `FAILED` に戻す処理を含む）と配信は印を付けずに消える                                                                                                                                                                                                                                                                                                                                                                                                    |
| `Restart`     | 再起動。再投入の最初の回は `initialDelay` = `interval` の後                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `Tick`        | 時間を 1 進める。定期の実行の時刻と、実行（古い出版を `FAILED` に戻す処理を含む）と配信の期限に来た操作が先に動く                                                                                                                                                                                                                                                                                                                                                                                                                                 |

定数のうち、`Interval`、`LockAtMostFor`、`WaitAge`、`Multiplier`、`MaxWait`、`MaxResubmissions`（全体の `abandon-after-attempts`）、`PolicyAbandon`（リスナーの方針の `abandon-after-attempts`。指定がなければ全体の値）、`InFlightStaleness` は設定値で、モデルの `ASSUME` が起動時の検査（`Properties` の `@AssertTrue`、`AbandonProperties` の `@Min(2)`、`Policy` の `@Min(1)`、方針の値を全体の値より小さくする `ResubmissionPolicies` の検査）と同じ条件を課す。
`MaxDue` は `ResubmissionPolicies.maxResubmissions` で、`isDue` が再投入する回数の上限である。
Spring Modulith が最初の配信と 1 回目の再投入の失敗に同じ 1 を渡すため、`PolicyAbandon` が 1 なら 0、2 以上なら同じ値になる。
`QueueMax <= LockAtMostFor` と `ListenerMax <= LockAtMostFor` は検査でなく前提で、ADR-079 が置いている。
最初の配信の空きの待ちが `wait-age` を超えると、`PUBLISHED` の出版を処理中に `FAILED` に戻しうる。
`application.yaml` の注記が既知としてリスナーの冪等性に頼っているため、どの設定も `QueueMax < WaitAge` にしている。
`ListenerMax`、`QueueMax`、`RunMax` は設定ではなく、動いているシステムの時間の上限である。

## 変異

変異は `model/mutants.toml` にあり、各項目の `change` が写すコードの退行である。
`startup-check-too-weak` は起動時の検査を緩める退行なので、対照は `control_overrides` で検査が受け付ける `in-flight-staleness` を使う。
この変異は、古い出版を `FAILED` に戻す処理をロックの中に移した後も、起動時の検査を 1 倍に緩めると処理中の出版を `FAILED` に戻すことを示す。
`lock-ignores-expiry` は `OneLockedRun` に加えて `InFlightNeverMarkedStale` も破り、ロックが、古い出版を `FAILED` に戻す処理と別のインスタンスの再投入を並べていることを示す。
古い出版を `FAILED` に戻す処理がロックの外へ戻る退行は、モデルでは回が一つの流れ（`RunStart` から `StaleFind`）で、ロックの取得と同じ段になるため、独立した変異にならない。
`lock-ignores-expiry` が同じ害を捕まえ、Java では `EventPublicationResubmitterStalenessTest` の `otherInstanceCannotInterleaveWithStaleCheck` が捕まえる。
`policy-one-resubmits-once` は、`abandon-after-attempts: 1` のリスナーにも 1 回の再投入を許す退行で、停止で処理中のまま残った最初の配信を、古い出版として `FAILED` に戻した後にジョブが再投入する。
Java では `ResubmissionPoliciesTest` の `abandonAndDueFollowListenerLimit` と、`EventPublicationResubmitterAbandonPolicyTest` の `restoredPublicationIsResubmittedOnlyByHand` が捕まえる。

## 範囲と前提

検査の範囲は次のとおりで、これを超える保証はない。

- 出版は一つ、インスタンスは 2 台、停止は設定ごとの `MaxCrashes`（0 回か 1 回）、全体の再投入の上限は 2 回、リスナーの方針の上限は 2 回か 1 回、時間は `Horizon` まで。
  出版は一つなので、リスナーの方針は一つの出版の `PolicyAbandon` で表し、方針の違うリスナーの出版が並ぶ場合は扱わない。
- DB の時刻とインスタンスの時計は一つの時計とみなす。
  時計のずれは扱わない。
- 古い出版を `FAILED` に戻す処理は、ロックを取った回の中で、古い行を読む `StaleFind` と印を付ける `StaleMark` に分けた。
  どちらも、その回の期限（`RunMax`）までのどの時刻でもよい。
  1 回の処理が三つの状態を順に読むことは、読む時刻を選べることで代える。
- 組み込みの Staleness Monitor は、閾値を書くと起動に失敗するため（`StalenessMonitorProperties`）、モデルにない。
- 手での 1 回の再投入（`EventPublicationResubmitOnce`）は扱わない。
  同じロックを取る運用者の入口で、ロックの中で古い出版を `FAILED` に戻してから、待ち時間と回数の上限を見ずに再投入する。
- `ResubmissionTracker` の数え方とメトリクス、上限到達の ERROR と上限を超えた `FAILED` の件数の ERROR のログは扱わない。
- `ABANDONED` は、`ARCHIVED` と同じく行が未完了の表から消えた終端の状態として扱う。
  `ResubmissionsCapped` は、`ABANDONED` にする判断を外しても `ResubmissionPolicies.isDue` の回数の条件だけで守られる。
  失敗を記録しない試行（停止の後に古い出版として `FAILED` に戻した出版、既知の発見 1 の遅れた印）で上限を超えて `FAILED` に戻った出版は、`AbandonPolicy` が呼ばれないためである（変異 `is-due-ignores-cap`）。
  古い一覧から再投入した出版は、渡される回数が行の回数より小さく、失敗しても `ABANDONED` にならずに `FAILED` に残りうる。
- 落ちたまま `PROCESSING` に残った出版は、最後の出版か再投入から `in-flight-staleness` まで回復しない。
- `ModuleRoleTransactionManager` のトランザクションごとのロールの切り替えは、ロックと状態の順序を変えないため扱わない。

## 既知の発見

1. `run-overruns-lock-stale-mark.cfg` で `InFlightNeverMarkedStale` が破れる。
   古い出版を `FAILED` に戻す処理（`markFailed(Status, Staleness)`）は、読んだ一覧の行を状態を確かめ直さずに更新する。
   回が `lock-at-most-for` を超えて長引くと、その間にロックが切れ、ほかのインスタンスが再投入した配信の出版を `FAILED` に戻す。
   TLC の経路は次のとおり。
   1. インスタンス 1 が最初の配信を executor に積んだまま停止する。
      出版の行は `PUBLISHED` のまま残り、インスタンス 1 は再起動する。
   2. インスタンス 2 の回がロックを取り、`wait-age` を過ぎたこの行を `findByStatus(PUBLISHED)` で古いと読む。
      その後、回が `lock-at-most-for` を超えて止まる。
   3. ロックの期限が切れ、インスタンス 1 の回がロックを取る。
      この行を古いと読んで `markFailed(id)` で `FAILED` にし、同じ回の `findFailedPublications` と `markResubmitted` で `RESUBMITTED` にして、配信を executor に積む。
   4. インスタンス 2 の回が、手順 2 で読んだ一覧のまま `markFailed(id)` を実行し、待ちの列にある配信の出版を `FAILED` に戻す。

   `ResubmissionOptionsLearningTest` の `stalenessMarksPublicationResubmittedAfterRead` が、読んだ後に再投入された出版も `FAILED` にする振る舞いを固定している。
   上流が直すと、そのテストが失敗する。

   害は、処理中の出版が再投入の対象に戻ることである。
   次の回の再投入が配信より先に来れば、リスナーが二重に動く（既知の発見 2）。
   `run-overruns-lock.cfg` の値では、方針の待ち時間（`wait-age` × `multiplier`）が配信より長いため、二重には動かない。
   まれなのは、古い行（停止で残った行か、最初の配信が `wait-age` より長く待った行）が要るうえに、回が古い行を読んでから `FAILED` にするまでの間に `lock-at-most-for`（本番 10 分）を超えて止まる必要があるためである。
   時間の予算（`lock-at-most-for` の半分）は再投入の候補を選ぶ間だけに掛かり、この間には掛からない。

2. `run-overruns-lock-long-listener.cfg` で `OneDeliveryAtATime` が破れる。
   既知の発見 1 の手順 4 のあと、配信が次の再投入の待ち時間より長く続くと、同じ出版を再投入する。
   TLC の経路は次のとおり。
   1. 既知の発見 1 の手順 1 から 3 と同じ。
      手順 3 の配信が始まり、行は `PROCESSING` になる。
   2. インスタンス 2 の回が、先に読んだ一覧のまま `markFailed(id)` を実行し、行を `FAILED` に戻す。
   3. 配信がまだ動いている間に、`ResubmissionPolicies.isDue` の待ち時間（`wait-age` × `multiplier`）を過ぎ、再投入のジョブが同じ出版をもう一度再投入する。
      リスナーが同時に二つ動く。

   害は、冪等でない副作用が二重に起きることである。
   既知の発見 1 の条件に加えて、空きの待ちとリスナーの処理が `wait-age` × `multiplier` より長く続く必要がある。
   `waitAge` の Javadoc と `application.yaml` の注記は、`wait-age` をリスナーの処理時間より十分に長くするとしている。

直った発見は次のとおり。

- Spring Modulith 2.1.1 では、古い出版を `FAILED` に戻す処理が `publication_date` から測るため、再投入が遅れた出版を処理中のまま `FAILED` に戻し、リスナーが同時に二つ動く経路があった。
  2.1.2 に上げ、起動時の検査を `in-flight-staleness` > 2 × `lock-at-most-for` に置き換えて直した（PR #497）。
  変異 `staleness-from-publication-date` がこの退行を捕まえる。
- 組み込みの Staleness Monitor を全インスタンスでロックの外で動かしていたときは、回が長引かなくても、既知の発見 1 と 2 と同じ経路があった（#556 より前の `late-stale-mark` の設定と、`late-stale-mark-long-listener`）。
  古い出版を `FAILED` に戻す処理を再投入のロックの中に移して直した（#556）。

## 同期の記録

同期コミット：`648c83ddf1709bb64b0e1c3ff368ddd5bf796754`（main。#741 の Pull Request の土台）

#741 の Pull Request のコード（方針ごとの `abandon-after-attempts`、`ResubmissionPolicies` の `AbandonPolicy` と `maxResubmissions`）で、「変更がこの検証に関わるか」のファイルと対応表がモデルと一致することを確かめ、`PolicyAbandon`、`MaxDue`、`NoResubmissionWhenAbandonOnFirst`、`policy-abandon-on-first.cfg`、変異 `policy-one-resubmits-once` を足した。
前の同期コミットからの main の変更は、#556 の squash merge（56361d77）、メールの依存と設定（#732）、テストの fork の数（#744）で、#556 の Pull Request のコードは前の同期で確かめ済み、他の二つは関わる節に触れない。
読んだライブラリは、Spring Modulith 2.2.0 の `AbandonPolicies`（利用者の `AbandonPolicy` を順に聞き、`ABANDON` と `RETAIN` で既定を飛ばす）と `EventPublicationAutoConfiguration.abandonPolicies`（`AbandonPolicy` の bean を `orderedStream` で集める）である。

前の同期（`34e927baf0ba528776da46f805d9f0d1e129f51f`、#556 の Pull Request の土台）では、#556 の Pull Request のコード（古い出版を `FAILED` に戻す処理を再投入のロックの中に移した後）で、「変更がこの検証に関わるか」のファイルと対応表がモデルと一致することを確かめた。
前の同期コミット（`91119568e0d323a37290e4474028c0c9c3a9b77a`）からの、`shared.eventresubmission` への移動、`FailedPublicationSweep` への集約、Spring Boot 4.2.0 への更新も読んだ。
読んだライブラリは、Spring Modulith 2.2.0 の `DefaultEventPublicationRegistry`（`markStalePublicationsFailed`、`markFailed(Status, Staleness)`、`processFailedPublications`、`markFailedOrAbandoned`）、`Staleness`、`StalenessProperties`、`StalenessMonitorConfiguration`、`EventPublicationAutoConfiguration`、`JdbcEventPublicationRepositoryV2`（行の読み出しで型を読めないイベントを落とすこと）、ShedLock 7.11.0 の `DefaultLockingTaskExecutor`、Spring Framework 7.1.0 の `SimpleAsyncTaskScheduler`、Spring Boot 4.2.0 の `DefaultTaskSchedulerConfiguration` と `TaskSchedulingConfigurations` である。
前の同期で読んだ ShedLock 7.11.0 の `JooqStorageAccessor`、Spring Modulith 2.2.0 の `AbandonPolicies`、`ResubmissionProperties`、`DefaultEventPublication`、`JdbcEventPublicationRepositoryV2` の `markFailed`、`markResubmitted`、`markAbandoned`、`CompletionRegisteringAdvisor`、`PersistentApplicationEventMulticaster`、`ResubmissionOptions` は版が変わっていないため、読み直していない。
