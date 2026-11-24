---
name: formal-verify-event-resubmission
description: 失敗したイベント出版の再投入を、時間つきの TLA+ のモデルで、2 台のインスタンスと停止を含めて全順序検査する。再投入のジョブ、ロック、方針、Staleness Monitor、event-publication.resubmission と spring.modulith.events の設定、Spring Modulith か ShedLock の版を変えるときと、リスナーの二重実行や再投入の漏れを疑うときに使う。
---

# イベント出版の再投入の形式検証

一つのイベント出版について、2 台のインスタンスの再投入のジョブ、ShedLock のロック、Staleness Monitor、リスナーの配信、インスタンスの停止と再起動を、離散の時間の上ですべての順序で検査する。
道具は TLA+ の TLC である。
問いが「時間の条件と複数の台の実行がどう重なっても約束が守られるか」であり、テストではロックの期限切れや実行の遅れの重なりを作れないためである。
検査の実行、結果の読み方、切り分け、モデルの直し方、同期コミットの進め方は `docs/agents/formal-verification.md` に従う。

## 実行する

```bash
tooling/formal-verify/check.sh .agents/skills/formal-verify-event-resubmission
tooling/formal-verify/mutants.py .agents/skills/formal-verify-event-resubmission
```

## 約束（不変条件）

| 名前                       | 約束                                                                                                                                                         | 破れたときの害                                     |
| -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------- |
| `OneDeliveryAtATime`       | 一つの出版のリスナーが、同時に二つ動かない（待ちの列にあるものも含む）。                                                                                     | 冪等でない副作用が二重に起きる。                   |
| `ResubmissionsCapped`      | ジョブが一つの出版を再投入する回数は `abandon-after-attempts` まで。                                                                                         | 回復しない出版を際限なく流し、上限の警報も遅れる。 |
| `OneLockedRun`             | ロックの中の処理を、同時に 2 台が実行しない。                                                                                                                | 古い一覧で同じ出版を重ねて再投入する。             |
| `InFlightNeverMarkedStale` | Staleness Monitor は、待ちの列にあるか実行中の配信の出版を `FAILED` に戻さない（起動時の検査 `isInFlightStalenessLongerThanTwiceLockAtMostFor` の約束）。 | 処理中の出版がまた再投入の対象になる。             |

## 設定と期待する結果

時間の単位は任意で、定数は小さな整数にしてある。
結果が示すのは「実行が `lock-at-most-for` の半分より長い」のような値の大小関係であり、本番の分や秒の値ではない。
どの設定も、`in-flight-staleness` を起動時の検査が許す最小（`lock-at-most-for` の 2 倍 + 1）にしている。
期待する結果の括弧の中は、`TypeOK` のほかに検査する不変条件である。

| 設定                                | 内容                                                                                                                                                                | 期待する結果                                                                     |
| ----------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------- |
| `documented.cfg`                    | 起動時の検査を通る設定と、`application.yaml` の注記が置く前提（リスナーの処理が `wait-age` に収まる、空きの待ちが `wait-age` より短い、実行が `lock-at-most-for` より短い）。停止は 1 回 | pass（`OneDeliveryAtATime`、`ResubmissionsCapped`、`OneLockedRun`）              |
| `late-stale-mark.cfg`               | `documented.cfg` と同じ値                                                                                                                                           | `InFlightNeverMarkedStale` が破れる（既知の発見 1）                              |
| `run-overruns-lock.cfg`             | `documented.cfg` で、実行が `lock-at-most-for` より長引き、2 台が同時に動く                                                                                         | pass（`OneDeliveryAtATime`、`ResubmissionsCapped`。時間の予算が守る）            |
| `in-flight-fast-runs.cfg`           | 停止なし、各回の実行が時間の予算（`lock-at-most-for` の半分）のうちに出版へ届く                                                                                    | pass（`InFlightNeverMarkedStale`）                                               |
| `in-flight-slow-runs.cfg`           | 停止なし、実行が時間の予算を過ぎてから出版へ届くことがあり、再投入が何回も遅れる                                                                                    | pass（`InFlightNeverMarkedStale`）                                               |
| `listener-up-to-lock.cfg`           | 1 回の配信が `lock-at-most-for` まで続き、`wait-age` より長い。停止は 1 回                                                                                          | pass（`ResubmissionsCapped`、`OneLockedRun`）                                    |
| `late-stale-mark-long-listener.cfg` | `listener-up-to-lock.cfg` と同じ値                                                                                                                                  | `OneDeliveryAtATime` が破れる（既知の発見 2）                                    |
| `delivery-up-to-twice-lock.cfg`     | 停止なし、空きの待ちとリスナーの処理が、それぞれ `lock-at-most-for` まで続く（最初の配信の待ちは `wait-age` 未満）                                                 | pass（全不変条件。起動時の検査が 1 倍なら `InFlightNeverMarkedStale` が破れる） |

## 切り分けの手がかり

- 時間を進める `Tick` を止める義務（`MustActNow`）が、コードの定期の実行と期限に合っているかを見る。
  Staleness Monitor の遅れの上限を外したモデルで、架空の違反が出たことがある。
- Staleness Monitor の `StaleFind` と `StaleMark` の間に行の状態が変わった経路は、既知の発見 1 と同じ原因である。
- 停止のない設定では、出版の行が古くなる前に配信が終わるため、Staleness Monitor は何も印を付けない。
  そこで `InFlightNeverMarkedStale` が破れたら、配信の待ちか処理の上限（`QueueMax`、`ListenerMax`）か `in-flight-staleness` の測り方を疑う。

## 変更がこの検証に関わるか

次のコマンドが差分を出せば関わる。

```bash
skill=.agents/skills/formal-verify-event-resubmission/SKILL.md
git diff --stat "$(sed -n 's/^同期コミット：`\([0-9a-f]*\)`.*/\1/p' "$skill")" -- \
  backend/src/main/java/com/example/demo/shared/infrastructure/persistence/EventPublicationResubmitter.java \
  backend/src/main/java/com/example/demo/shared/infrastructure/persistence/EventPublicationResubmissionConfig.java \
  backend/src/main/java/com/example/demo/shared/infrastructure/persistence/ResubmissionPolicies.java \
  backend/src/test/java/com/example/demo/shared/infrastructure/persistence/ResubmissionOptionsLearningTest.java \
  backend/src/main/resources/application.yaml \
  backend/build.gradle
```

`application.yaml` は `spring.modulith.events`（`completion-mode`、`staleness`、`resubmission`）、`spring.task.execution`、`spring.task.scheduling`、`spring.threads.virtual`、`event-publication.resubmission` の節だけ、`build.gradle` は `springModulithVersion`、ShedLock、Spring Boot の版だけが関わる。
`spring.threads.virtual.enabled` のとき Spring Boot が使う `SimpleAsyncTaskScheduler` は、fixed delay のタスクを一つのスレッドで順に動かすため、同じインスタンスの再投入のジョブと Staleness Monitor は重ならない。
スケジューラを変えて二つが重なるようになったら、モデルの `ThreadFree` を直す。

## 対応表

モデルは `model/EventResubmission.tla` にある。
各アクションはコードの一つの SQL の文か、メモリの中の一つの判断である。

| アクション   | コード                                                                                                                                                                                                                       |
| ------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `RunStart`   | スケジューラのスレッドが空くと、`EventPublicationResubmitter.resubmitOnce` が `now` を取り、`executeWithLock` → ShedLock の `JooqStorageAccessor.updateRecord`（`lock_until <= now` の UPDATE、DB の時刻）                 |
| `RunRead`    | `FailedEventPublications.resubmit` → `findFailedPublications`（`status = 'FAILED'` の SELECT）。一覧はその回の間古いまま使う                                                                                                |
| `RunFinish`  | `withFilter` の述語（時間の予算と `ResubmissionPolicies.isDue`）、`markResubmitted`（`status != 'RESUBMITTED'` の UPDATE）と配信、ShedLock の `unlock`（`max(locked_at + lockAtLeastFor, now)`、`locked_by` が自分のとき） |
| `StaleFind`  | スケジューラのスレッドが空くと、`DefaultEventPublicationRegistry.markFailed(Status, Staleness)` の `findByStatus` と `getLastPublicationDate()`（`last_resubmission_date`）の絞り込み                                      |
| `StaleMark`  | 同じメソッドが読んだ一覧の各行に呼ぶ `JdbcEventPublicationRepositoryV2.markFailed(id)`（`completion_date IS NULL AND status != 'FAILED'` の UPDATE。状態と日時を確かめ直さない）                                           |
| `ExecStart`  | `CompletionRegisteringMethodInterceptor` → `markProcessing`。配信は `spring.task.execution` の executor の空きを待ってから始まる                                                                                             |
| `ExecSucceed` | `markCompleted`（archive への写しと削除）                                                                                                                                                                                   |
| `ExecFail`   | `markFailed` → `markFailedOrAbandoned`。`AbandonPolicies` の既定（`ResubmissionProperties.shouldAbandon`）が、渡された出版の `getCompletionAttempts()`（配信の `seen`。最初の配信は 1、再投入は `RunRead` で読んだ値）を `abandon-after-attempts` と比べ、それ以上なら `markAbandoned`（archive へ `ABANDONED` で写して削除。状態を確かめない）、未満なら `markFailed`（`status != 'FAILED'` の UPDATE） |
| `Crash`      | インスタンスの停止。ロックの行は `lock_until` まで残り、そのインスタンスの配信と Staleness Monitor の印は付かずに消える                                                                                                     |
| `Restart`    | 再起動。再投入の最初の回は `initialDelay` = `interval` の後                                                                                                                                                                  |
| `Tick`       | 時間を 1 進める。定期の実行の時刻、実行と配信の期限、Staleness Monitor の読む期限（`interval` + 1 回の実行）と印を付ける期限（読んでから 1 回の実行）に来た操作が先に動く                                                  |

定数のうち、`Interval`、`LockAtMostFor`、`WaitAge`、`Multiplier`、`MaxWait`、`MaxResubmissions`（`abandon-after-attempts`）、`InFlightStaleness` は設定値で、モデルの `ASSUME` が起動時の検査（`Properties` の `@AssertTrue` と `AbandonProperties` の `@Min(2)`）と同じ条件を課す。
`QueueMax <= LockAtMostFor` と `ListenerMax <= LockAtMostFor` は検査でなく前提で、ADR-079 が置いている。
最初の配信の空きの待ちが `wait-age` を超えると、`PUBLISHED` の出版を処理中に `FAILED` に戻しうる。
`application.yaml` の注記が既知としてリスナーの冪等性に頼っているため、どの設定も `QueueMax < WaitAge` にしている。
`ListenerMax`、`QueueMax`、`RunMax` は設定ではなく、動いているシステムの時間の上限である。

## 変異

変異は `model/mutants.toml` にあり、各項目の `change` が写すコードの退行である。
`startup-check-too-weak` は起動時の検査を緩める退行なので、対照は `control_overrides` で検査が受け付ける `in-flight-staleness` を使う。

## 範囲と前提

検査の範囲は次のとおりで、これを超える保証はない。

- 出版は一つ、インスタンスは 2 台、停止は設定ごとの `MaxCrashes`（0 回か 1 回）、再投入の上限は 2 回、時間は `Horizon` まで。
- DB の時刻とインスタンスの時計は一つの時計とみなす。
  時計のずれは扱わない。
- Staleness Monitor は、インスタンスごとに、古い行を読む `StaleFind` と印を付ける `StaleMark` に分けた。
  読むのは行が古くなってから `interval` と 1 回の実行のうち（スケジューラのスレッドが再投入のジョブで埋まっていれば、空いた時点）のどの時刻でもよく、印はその読み取りから 1 回の実行のうちに付ける。
  1 回の実行が三つの状態を順に読むことは、読む時刻を選べることで代える。
- 手での 1 回の再投入（`EventPublicationResubmitOnce`）は扱わない。
  同じロックを取る運用者の入口で、待ち時間と回数の上限を見ない。
- `ResubmissionTracker` の数え方とメトリクス、上限到達の ERROR と上限を超えた `FAILED` の件数の ERROR のログは扱わない。
- `ABANDONED` は、`ARCHIVED` と同じく行が未完了の表から消えた終端の状態として扱う。
  `ResubmissionsCapped` は、`ABANDONED` にする判断を外しても `ResubmissionPolicies.isDue` の回数の条件だけで守られる。
  失敗を記録しない試行（停止、Staleness Monitor の遅れた印）で上限を超えて `FAILED` に戻った出版は、`AbandonPolicy` が呼ばれないためである（変異 `is-due-ignores-cap`）。
  古い一覧から再投入した出版は、渡される回数が行の回数より小さく、失敗しても `ABANDONED` にならずに `FAILED` に残りうる。
- 落ちたまま `PROCESSING` に残った出版は、最後の出版か再投入から `in-flight-staleness` まで回復しない。
- `ModuleRoleTransactionManager` のトランザクションごとのロールの切り替えは、ロックと状態の順序を変えないため扱わない。

## 既知の発見

1. `late-stale-mark.cfg` で `InFlightNeverMarkedStale` が破れる。
   Staleness Monitor の `markFailed(Status, Staleness)` が、読んだ一覧の行を状態を確かめ直さずに更新するため、ほかのインスタンスが再投入した配信の出版を `FAILED` に戻す。
   TLC の経路は次のとおり。
   1. インスタンス 1 が最初の配信を executor に積んだまま停止する。
      出版の行は `PUBLISHED` のまま残り、インスタンス 1 は再起動する。
   2. `wait-age` を過ぎ、両方のインスタンスの Staleness Monitor が `findByStatus(PUBLISHED)` でこの行を古いと読む。
   3. インスタンス 1 の Staleness Monitor が `markFailed(id)` で行を `FAILED` にする。
   4. インスタンス 1 のスケジューラのスレッドが空き、`EventPublicationResubmitter` がロックを取り、`findFailedPublications` でこの行を読み、`markResubmitted` で `RESUBMITTED` にして、配信を executor に積む。
   5. インスタンス 2 の Staleness Monitor が、手順 2 で読んだ一覧のまま `markFailed(id)` を実行し、待ちの列にある配信の出版を `FAILED` に戻す。

   `ResubmissionOptionsLearningTest` の `stalenessMarksPublicationResubmittedAfterRead` が、この振る舞いを `DefaultEventPublicationRegistry` を通る経路で固定している。
   上流が直すと、そのテストが失敗する。

   害は、処理中の出版が再投入の対象に戻ることである。
   次の回の再投入が配信より先に来れば、リスナーが二重に動く（既知の発見 2）。
   `documented.cfg` の値では、方針の待ち時間（`wait-age` × `multiplier`）が配信より長いため、二重には動かない。
   まれなのは、古い行が要る（停止で残った行か、最初の配信が `wait-age` より長く待った行）うえに、一つのインスタンスの `findByStatus` と `markFailed` の間に、ほかのインスタンスの Staleness Monitor の印と再投入の両方が入る必要があるためである。
   この間は一つの SELECT と、読んだ行への UPDATE の繰り返しだけで、古い行が多いか DB が遅いときに長くなる。

2. `late-stale-mark-long-listener.cfg` で `OneDeliveryAtATime` が破れる。
   既知の発見 1 の手順 5 のあと、配信が次の再投入の待ち時間より長く続くと、同じ出版を再投入する。
   TLC の経路は次のとおり。
   1. 既知の発見 1 の手順 1 から 4 と同じ。
      手順 4 の配信が始まり、行は `PROCESSING` になる。
   2. もう一方のインスタンスの Staleness Monitor が、先に読んだ一覧のまま `markFailed(id)` を実行し、行を `FAILED` に戻す。
   3. 配信がまだ動いている間に、`ResubmissionPolicies.isDue` の待ち時間（`wait-age` × `multiplier`）を過ぎ、再投入のジョブが同じ出版をもう一度再投入する。
      リスナーが同時に二つ動く。

   害は、冪等でない副作用が二重に起きることである。
   既知の発見 1 の条件に加えて、空きの待ちとリスナーの処理が `wait-age` × `multiplier` より長く続く必要がある。
   `waitAge` の Javadoc と `application.yaml` の注記は、`wait-age` をリスナーの処理時間より十分に長くするとしている。

直った発見として、Spring Modulith 2.1.1 では Staleness Monitor が `publication_date` から測るため、再投入が遅れた出版を処理中のまま `FAILED` に戻し、リスナーが同時に二つ動く経路があった。
2.1.2 に上げ、起動時の検査を `in-flight-staleness` > 2 × `lock-at-most-for` に置き換えて直した（PR #497）。
変異 `staleness-from-publication-date` がこの退行を捕まえる。

## 同期の記録

同期コミット：`91119568e0d323a37290e4474028c0c9c3a9b77a`（main。#562 の Pull Request の土台）

#562 の Pull Request のコード（回数の上限を `abandon-after-attempts` に任せた後）で、「変更がこの検証に関わるか」のファイルと対応表がモデルと一致することを確かめた。
読んだライブラリは、Spring Modulith 2.2.0 の `DefaultEventPublicationRegistry`（`markFailedOrAbandoned`、`markStalePublicationsFailed`）、`AbandonPolicies`、`ResubmissionProperties`、`DefaultEventPublication`、`JdbcEventPublicationRepositoryV2`（`markAbandoned`、`markResubmitted`、`markFailed`）、`CompletionRegisteringAdvisor`（`CompletionRegisteringMethodInterceptor`、`spring.modulith.events.completion: in-interceptor`）、`PersistentApplicationEventMulticaster`、`StalenessMonitorConfiguration`、`ResubmissionOptions`、ShedLock 7.11.0 の `JooqStorageAccessor`、Spring Framework 7.0.10 の `SimpleAsyncTaskScheduler`、Spring Boot 4.1.2 の `DefaultTaskSchedulerConfiguration` である。
