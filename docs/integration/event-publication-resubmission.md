---
type: Architecture
title: イベント出版の再投入の全体像
description: 失敗したイベント出版を自動で再投入する仕組みの構成要素と、読む文書の選び方を示す。この仕組みを初めて調べるとき、どの文書を読むか迷ったときに読む。
tags: [architecture, integration, async, spring-modulith, resilience]
---

# イベント出版の再投入の全体像

失敗したイベント出版を自動で再投入する仕組みの地図である。
規則と理由は書かず、リンク先を正文とする。

## 仕組みの全体像

リスナーが例外を送出すると、そのイベント出版は `FAILED` の状態でレジストリに残る。
定期のジョブが、待ち時間を過ぎた `FAILED` の出版を回数の上限まで再投入する。
上限の回数目の再投入でまた失敗した出版は、Spring Modulith が `ABANDONED` にし、定期のジョブが ERROR のログで知らせ、運用者が手で扱う。
設計の理由と代替案は [ADR-079](../adr/ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md) にある。

出版の状態（`PUBLISHED`、`PROCESSING`、`COMPLETED`、`FAILED`、`RESUBMITTED`、`ABANDONED`）の定義は[非同期処理の失敗時の再試行と回復](async-failure-recovery.md)にある。

構成要素は次のとおりである。

- **定期の再投入のジョブ**（`EventPublicationResubmitter`）：`FAILED` の出版を条件に合うものだけ再投入する。
- **失敗した出版の掃き取り**（`FailedPublicationSweep`）：ロックを取り、古い出版を `FAILED` に戻してから、選んだ `FAILED` の出版を `max-per-run` 件まで再投入し、`RESUBMITTED` へ進んだ出版を返す。
  定期のジョブと手での 1 回の再投入が、選ぶ条件、ロックの取り方（1 回だけ試す、取れるまで待つ）、再投入のあとの扱いだけを変えて同じ手順を使う。
- **ShedLock**：ロックの行で、古い出版を `FAILED` に戻す処理と再投入を、同時に 1 つのインスタンスへ絞る。
- **リスナーの方針**（`ResubmissionPolicies`）：出版をリスナーの待ち時間と回数の上限の方針に対応づけ、再投入してよいかを決める。
  Spring Modulith の `AbandonPolicy` も実装し、リスナーの `abandon-after-attempts` に達した出版を `ABANDONED` にする。
- **回数の上限**（`spring.modulith.events.resubmission.abandon-after-attempts`）：Spring Modulith が、上限の回数目の再投入で失敗した出版を `ABANDONED` にする。
  方針に `abandon-after-attempts` がなければ、この値が全リスナーの既定になる。
- **手での 1 回の再投入のジョブ**（`EventPublicationResubmitOnce`）：運用者が 1 回だけ起動し、同じロックを取って再投入する。対象は `all`、`policy:<名前>`、`event-type:<クラス>` で、起動時に `Target` に読む。
- **古い出版を `FAILED` に戻す処理**（`StalePublications`）：落ちたまま残った出版を、完了していないことと状態ごとの古さを確かめる 1 文の UPDATE で `FAILED` に戻す。掃き取りがロックの中で、再投入の前に呼ぶ。Spring Modulith の `markStalePublicationsFailed` と組み込みの Staleness Monitor は使わず、その閾値を書くと起動に失敗する。
- **archive の行の保持期限の削除**（`EventPublicationArchiveRetentionConfig`）：`COMPLETED` の行を 7 日で消し、`ABANDONED` の行を残す（[ADR-120](../adr/ADR-120-keep-abandoned-event-publications-out-of-archive-retention.md)、[改廃の方法](../database/table-retention-and-personal-data.md#改廃の方法)）。
- **`in-flight-staleness`**：`PROCESSING` と `RESUBMITTED` の出版を、最後の出版か再投入の時刻から数えて `FAILED` に戻すまでの時間である。`PUBLISHED` の出版は `wait-age` で測る。

## Resilience4j との関係

外部の呼び出しを再試行する層は、再投入の 1 つだけである。
Resilience4j は Client の circuit breaker だけを担い、Client は即時の再試行をしない。
再試行の層と circuit breaker を残す理由は [ADR-019](../adr/ADR-019-define-resilience-and-capacity-guardrails.md) に、リスナーでその場の再試行に任せる案の却下は [ADR-079 の選択肢 9](../adr/ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md) にある。
circuit breaker が open の間に回数を消費する限界は、ADR-079 の Consequences にある。

## どの文書を読むか

| 場面                                                                            | 文書                                                                                                                                           |
| ------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| 再投入を設計した理由と代替案を知りたい                                          | [ADR-079](../adr/ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)                                                     |
| 外部呼び出しのタイムアウトと circuit breaker の値を知りたい                     | [ADR-019](../adr/ADR-019-define-resilience-and-capacity-guardrails.md)                                                                         |
| 出版の状態と再投入の規約を確かめたい                                            | [非同期処理の失敗時の再試行と回復](async-failure-recovery.md)                                                                                  |
| 上限に達した出版の ERROR に対応したい、手で再投入したい、自動の再投入を止めたい | [イベント出版の再投入のログイベント](../observability/runbook-event-publication-resubmission.md)                                               |
| 非同期のテストで待ちを書きたい                                                  | [ADR-080](../adr/ADR-080-wait-for-async-conditions-with-awaitility.md)、[バックエンドのテストコードの書き方](../backend/testing-code-style.md) |
| ShedLock の行や出版の表を使うテストを書きたい                                   | [バックエンドの DB テスト](../backend/testing-database.md)                                                                                     |
| PIT が遅い、止まる理由と設定の決定を知りたい                                    | [ADR-076](../adr/ADR-076-run-mutation-tests-per-change-and-tighten-coverage-gates.md)                                                          |

## 環境変数

次の 5 つの環境変数で挙動を切り替える。
意味の正文は[リスナーの方針](../observability/runbook-event-publication-resubmission.md#リスナーの方針)と ADR-079 にある。

| 変数                                                 | 意味                                      |
| ---------------------------------------------------- | ----------------------------------------- |
| `EVENT_PUBLICATION_RESUBMISSION_ENABLED`             | 定期の実行を動かすか                      |
| `EVENT_PUBLICATION_RESUBMISSION_WAIT_AGE`            | 最初の待ち時間                            |
| `EVENT_PUBLICATION_RESUBMISSION_INTERVAL`            | 定期の実行の間隔                          |
| `EVENT_PUBLICATION_RESUBMISSION_IN_FLIGHT_STALENESS` | 処理中の出版を `FAILED` に戻すまでの時間  |
| `EVENT_PUBLICATION_ARCHIVE_RETENTION_ENABLED`        | archive の行の保持期限の削除を動かすか    |

設定箇所は次のとおりである。

- **読み込み**：`backend/src/main/resources/application.yaml` の `event-publication.resubmission` と `event-publication.archive-retention`。
- **ローカル**：`.env.example` から作る `.env`。
  値は `.env.example` を見る。
- **テスト**：`.env.test.example` から作る `.env.test`。
  値は `.env.test.example` を見る。
- **コンテナ**：`docker/compose.yml` が `.env` の値を渡す。
  5 つとも必須である。
- **本番**：このリポジトリに ECS のタスク定義はなく、値は未設定である。
  想定する値は ADR-079 にある。
