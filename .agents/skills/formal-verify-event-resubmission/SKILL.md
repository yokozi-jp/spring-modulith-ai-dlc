---
name: formal-verify-event-resubmission
description: 失敗したイベント出版の再投入（EventPublicationResubmitter、ShedLock のロック、ResubmissionPolicies、Spring Modulith の Staleness Monitor、in-flight-staleness の起動時の検査）を、時間つきの TLA+ のモデルで、2 台のインスタンスと停止を含めて全順序検査する。再投入のジョブ、ロック、方針、event-publication.resubmission と spring.modulith.events の設定、Spring Modulith か ShedLock の版を変えるときと、リスナーの二重実行や再投入の漏れを疑うときに使う。
---

# イベント出版の再投入の形式検証

一つのイベント出版について、2 台のインスタンスの再投入のジョブ、ShedLock のロック、Staleness Monitor、リスナーの配信、インスタンスの停止と再起動を、離散の時間の上ですべての順序で検査する。
道具は TLA+ の TLC である。
問いが「時間の条件と複数の台の実行がどう重なっても約束が守られるか」であり、テストではロックの期限切れや実行の遅れの重なりを作れないためである。

## 約束（不変条件）

| 名前                       | 約束                                                                                                   | 破れたときの害                                     |
| -------------------------- | ------------------------------------------------------------------------------------------------------ | -------------------------------------------------- |
| `OneDeliveryAtATime`       | 一つの出版のリスナーが、同時に二つ動かない（待ちの列にあるものも含む）。                               | 冪等でない副作用が二重に起きる。                   |
| `ResubmissionsCapped`      | ジョブが一つの出版を再投入する回数は `max-resubmissions` まで。                                        | 回復しない出版を際限なく流し、上限の警報も遅れる。 |
| `OneLockedRun`             | ロックの中の処理を、同時に 2 台が実行しない。                                                          | 古い一覧で同じ出版を重ねて再投入する。             |
| `InFlightNeverMarkedStale` | Staleness Monitor は、待ちの列にあるか実行中の配信の出版を `FAILED` に戻さない（起動時の検査 `isInFlightStalenessLongerThanTwiceLockAtMostFor` の約束）。 | 処理中の出版がまた再投入の対象になる。             |

## 実行する

リポジトリのルートで実行する。

```bash
.agents/skills/formal-verify-event-resubmission/scripts/check.sh
.agents/skills/formal-verify-event-resubmission/scripts/mutants.py
```

- `check.sh` は `model/*.cfg` をすべて検査し、各設定の 1 行目の `expect:` と結果が一致すれば 0 で終わる。全体で 20 秒ほどかかる。
  初回だけ TLC（`tla2tools.jar` v1.7.4、SHA-256 で確かめる）を `~/.cache/formal-verify-tla/` に取得し、Java（`temurin-25.0.5+8.0.LTS`）を mise で入れるため、ネットワークが要る。
  TLC の作業ファイルは `~/.cache/formal-verify-tla/runs/` に残る。`/tmp` が tmpfs で小さい環境でも溢れないようにするためである。
  Kiro Web では先に `source docs/local-env-setup/kiro-web.sh` を実行する。
- `mutants.py` は、モデルの写しにコードの退行を一つずつ入れ、それぞれが守る不変条件を TLC が破ると報告することを確かめる。
  一つでも見逃すと 1 で終わる。モデルを変えたら必ず実行する。

時間の単位は任意で、定数は小さな整数にしてある。
結果が示すのは「実行が `lock-at-most-for` の半分より長い」のような値の大小関係であり、本番の分や秒の値ではない。

| 設定                      | 内容                                                                                                   | 期待する結果                          |
| ------------------------- | ------------------------------------------------------------------------------------------------------ | ------------------------------------- |
| `documented.cfg`          | 起動時の検査を通る設定と、`application.yaml` の注記が置く前提（リスナーの処理と空きの待ちが `wait-age` に収まる、実行が `lock-at-most-for` より短い）。停止は 1 回 | 全不変条件が成り立つ                  |
| `run-overruns-lock.cfg`   | 実行が `lock-at-most-for` より長引き、2 台が同時に動く                                                 | `OneLockedRun` 以外が成り立つ（時間の予算が守る） |
| `in-flight-fast-runs.cfg` | 停止なし、各回の実行が時間の予算（`lock-at-most-for` の半分）のうちに出版へ届く                       | `InFlightNeverMarkedStale` が成り立つ |
| `in-flight-slow-runs.cfg` | 停止なし、実行が時間の予算を過ぎてから出版へ届くことがあり、再投入が何回も遅れる                       | `InFlightNeverMarkedStale` が成り立つ |
| `listener-up-to-lock.cfg` | 1 回の配信が `lock-at-most-for` まで続き、`wait-age` より長い                                          | 全不変条件が成り立つ                  |
| `delivery-up-to-twice-lock.cfg` | 空きの待ちとリスナーの処理が、それぞれ `lock-at-most-for` まで続く（最初の配信の待ちは `wait-age` 未満） | `InFlightNeverMarkedStale` が成り立つ（1 倍の検証では破れる） |

どの設定も、`in-flight-staleness` を起動時の検査が許す最小（`lock-at-most-for` の 2 倍 + 1）にしている。
どれかが破れたら、「結果を読む」で切り分ける。

## 結果を読む

TLC の結果は必ず `/statechart` スキルで読む。
不変条件の違反は最短の経路として平易な言葉で示され、成り立つときは状態の遷移図を描く。
`/statechart` はリポジトリの外の作業用のフォルダで動かす。
`model/` を写し、状態と遷移の言葉を決める view を書いて渡す（書き方は `/statechart` にある）。
状態の数が多いため、`/statechart` の図は違反の経路だけになることが多い。

違反が出たら、本物の不具合かモデルの誤りかを次の順に切り分ける。

1. 経路の各ステップを、下の「対応表」でコードの場所に置き換える。
2. 各ステップがコードで一つの不可分な操作か、その順序と時刻がコードで起こりうるかを確かめる。
   特に、時間を進める `Tick` を止める義務（`MustActNow`）が、コードの定期の実行と期限に合っているかを見る。
   Staleness Monitor の遅れの上限を外したモデルで、架空の違反が出たことがある。
3. 起こりうるなら本物の不具合である。不変条件やモデルを緩めて通さない。
   経路をコードの言葉の手順で利用者に報告し、製品のコードは利用者が頼んだときだけ直す。
4. 起こりえないなら、モデルのどの行がコードと違うかを直し、`check.sh` と `mutants.py` を流し直す。

## 変更がこの検証に関わるか

次のファイルの差分があれば関わる。

```bash
git diff --stat <同期コミット> -- \
  backend/src/main/java/com/example/demo/shared/infrastructure/persistence/EventPublicationResubmitter.java \
  backend/src/main/java/com/example/demo/shared/infrastructure/persistence/EventPublicationResubmissionConfig.java \
  backend/src/main/java/com/example/demo/shared/infrastructure/persistence/ResubmissionPolicies.java \
  backend/src/main/java/com/example/demo/shared/infrastructure/persistence/EventPublicationResubmitOnce.java \
  backend/src/main/resources/application.yaml \
  backend/build.gradle
```

`application.yaml` は `spring.modulith.events`（`completion-mode`、`staleness`）、`spring.task.execution`、`event-publication.resubmission` の節だけ、`build.gradle` は `springModulithVersion` と ShedLock の版だけが関わる。
Spring Modulith か ShedLock の版が上がったら、下の対応表のライブラリの行を新しい版のソースで読み直す。
読んだのは Spring Modulith 2.1.2 の `JdbcEventPublicationRepositoryV2`、`DefaultEventPublicationRegistry`、`CompletionRegisteringMethodInterceptor`、`StalenessMonitorConfiguration` と、ShedLock 7.11.0 の `JooqStorageAccessor` である。

## 対応表

モデルは `model/EventResubmission.tla` にある。
各アクションはコードの一つの SQL の文か、メモリの中の一つの判断である。

| アクション     | コード                                                                                                                                                        |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `RunStart`     | `EventPublicationResubmitter.resubmitOnce` が `now` を取り、`executeWithLock` → ShedLock の `JooqStorageAccessor.updateRecord`（`lock_until <= now` の UPDATE、DB の時刻） |
| `RunRead`      | `FailedEventPublications.resubmit` → `findFailedPublications`（`status = 'FAILED'` の SELECT）。一覧はその回の間古いまま使う                                  |
| `RunFinish`    | `withFilter` の述語（時間の予算と `ResubmissionPolicies.isDue`）、`markResubmitted`（`status != 'RESUBMITTED'` の UPDATE）と配信、ShedLock の `unlock`（`max(locked_at + lockAtLeastFor, now)`、`locked_by` が自分のとき） |
| `Stale`        | `DefaultEventPublicationRegistry.markStalePublicationsFailed`（2.1.2 は `getLastPublicationDate()`、つまり `last_resubmission_date` から測る。`markFailed` は状態を確かめ直さない） |
| `ExecStart`    | `CompletionRegisteringMethodInterceptor` → `markProcessing`。配信は `spring.task.execution` の executor の空きを待ってから始まる                              |
| `ExecSucceed`  | `markCompleted`（archive への写しと削除）                                                                                                                     |
| `ExecFail`     | `markFailed`                                                                                                                                                  |
| `Crash`        | インスタンスの停止。ロックの行は `lock_until` まで残り、そのインスタンスの配信は印を付けずに消える                                                           |
| `Restart`      | 再起動。再投入の最初の回は `initialDelay` = `interval` の後                                                                                                   |
| `Tick`         | 時間を 1 進める。定期の実行の時刻、実行と配信の期限、Staleness Monitor の遅れの上限（`interval` + 1 回の実行）に来た操作が先に動く                            |

定数のうち、`Interval`、`LockAtMostFor`、`WaitAge`、`Multiplier`、`MaxWait`、`MaxResubmissions`、`InFlightStaleness` は設定値で、モデルの `ASSUME` が起動時の検査（`Properties` の `@AssertTrue`）と同じ条件を課す。
`QueueMax <= LockAtMostFor` と `ListenerMax <= LockAtMostFor` は検査でなく前提で、ADR-079 が置いている。
最初の配信の空きの待ちが `wait-age` を超えると、`PUBLISHED` の出版を処理中に `FAILED` に戻しうる。`application.yaml` の注記が既知としてリスナーの冪等性に頼っているため、どの設定も `QueueMax < WaitAge` にしている。
`ListenerMax`、`QueueMax`、`RunMax` は設定ではなく、動いているシステムの時間の上限である。

変異（`scripts/mutants.py` の `MUTANTS`）と、それが写すコードの退行は次のとおり。

| 変異                              | コードの退行                                                                         | 破れる不変条件                                  |
| --------------------------------- | ------------------------------------------------------------------------------------ | ----------------------------------------------- |
| `no-time-budget`                  | `EventPublicationResubmitter.resubmit` の `withFilter` から時間の予算を外す          | `ResubmissionsCapped`、`OneDeliveryAtATime`     |
| `is-due-ignores-cap`              | `ResubmissionPolicies.isDue` から回数の上限を外す                                    | `ResubmissionsCapped`                           |
| `lock-ignores-expiry`             | ロックを期限を見ずに取る（ロックの provider を誤って差し替えるなど）                 | `OneLockedRun`                                  |
| `staleness-from-publication-date` | Spring Modulith を 2.1.1 に戻す（Staleness Monitor が `publication_date` から測る）  | `InFlightNeverMarkedStale`                      |
| `startup-check-too-weak`          | 起動時の検査が、空きの待ちとリスナーの処理の合計より短い `in-flight-staleness` を通す | `InFlightNeverMarkedStale`                      |

## 意図した変更でモデルを直す

1. 変えたコードを対応表で引き、対応するアクションをコードの新しい振る舞いに合わせて書き換える。
   新しい SQL の文や判断が増えたらアクションを足し、`Next` と対応表に足す。
   新しい期限や定期の実行が増えたら、`MustActNow` に足す。
2. 設定値の検査を変えたら、`ASSUME` を同じ条件にする。
3. 新しい約束が増えたら不変条件を足し、それを破る変異を `MUTANTS` と上の表に足す。
4. `check.sh` と `mutants.py` を流し、違反が出たら「結果を読む」で切り分ける。
5. コードとモデルが一致することを確かめてから、下の同期コミットを新しいコミットに進める。
   確かめていないコミットへ進めない。

## 範囲と前提

検査の範囲は次のとおりで、これを超える保証はない。

- 出版は一つ、インスタンスは 2 台、停止は設定ごとの `MaxCrashes`（0 回か 1 回）、再投入の上限は 2 回、時間は `Horizon` まで。
- DB の時刻とインスタンスの時計は一つの時計とみなす。時計のずれは扱わない。
- Staleness Monitor は「古くなってから `interval` と 1 回の実行のうちに、どの時刻でも印を付けうる」と抽象化した。2 台の監視のどの予定も、この範囲に入る。
- 手での 1 回の再投入（`EventPublicationResubmitOnce`）、`ResubmissionTracker` の数え方とメトリクス、上限到達の ERROR のログは扱わない。
- 落ちたまま `PROCESSING` に残った出版は、最後の出版か再投入から `in-flight-staleness` まで回復しない。
- `ModuleRoleTransactionManager` のトランザクションごとのロールの切り替えは、ロックと状態の順序を変えないため扱わない。

## 過去の発見

Spring Modulith 2.1.1 では、Staleness Monitor が `publication_date` から測るため、再投入が遅れた出版を処理中のまま `FAILED` に戻し、リスナーが同時に二つ動く経路があった。
2.1.2 に上げ、起動時の検査を `in-flight-staleness` > 2 × `lock-at-most-for` に置き換えて直した（PR #497）（変異 `staleness-from-publication-date` がこの退行を捕まえる）。

## 同期の記録

同期コミット：`cf79ec53c58d561e357b3e6c8dcd5af02852e058`（ブランチ `fix/modulith-staleness-from-last-resubmission`。main に入ったら、そのマージのコミットで確かめ直して進める）

このコミットで、「変更がこの検証に関わるか」のファイルと対応表がモデルと一致することを確かめた。
関わるファイルがこのコミットより後に変わっていたら、モデルを読み直してから検査の結果を信じる。
読み直しには `/maintain-formal-verification-skill` を使う。
