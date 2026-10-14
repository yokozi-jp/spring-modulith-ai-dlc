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
定期のジョブが、待ち時間を過ぎた `FAILED` の出版を方針の上限の回数まで再投入する。
上限に達した出版は ERROR のログで知らせ、運用者が手で扱う。
設計の理由と代替案は [ADR-079](../adr/ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md) にある。

出版の状態（`PUBLISHED`、`PROCESSING`、`COMPLETED`、`FAILED`、`RESUBMITTED`）の定義は[非同期処理の失敗時の再試行と回復](async-failure-recovery.md)にある。

構成要素は次のとおりである。

- **定期の再投入のジョブ**（`EventPublicationResubmitter`）：`FAILED` の出版を条件に合うものだけ再投入する。
- **ShedLock**：ロックの行で、定期のジョブを同時に 1 つのインスタンスへ絞る。
- **手での 1 回の再投入のジョブ**（`EventPublicationResubmitOnce`）：運用者が 1 回だけ起動し、同じロックを取って再投入する。
- **Staleness Monitor**：落ちたまま残った出版を `FAILED` に戻す。
- **`in-flight-staleness`**：`PROCESSING` と `RESUBMITTED` の出版を `FAILED` に戻すまでの時間である。

## Resilience4j との関係

Resilience4j は client adapter の即時の再試行と遮断を担い、再投入は持続的な回復を担う。
層が違うため、補完関係にある。
外部呼び出しの retry と circuit breaker の位置づけは [ADR-019](../adr/ADR-019-define-resilience-and-capacity-guardrails.md) に、event worker で使う案の却下は [ADR-079 の選択肢 9](../adr/ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md) にある。
circuit breaker が open の間に回数を消費する限界は、ADR-079 の Consequences にある。

## どの文書を読むか

| 場面                                                                            | 文書                                                                                                                                           |
| ------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| 再投入を設計した理由と代替案を知りたい                                          | [ADR-079](../adr/ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)                                                     |
| 外部呼び出しの retry と circuit breaker の値を知りたい                          | [ADR-019](../adr/ADR-019-define-resilience-and-capacity-guardrails.md)                                                                         |
| 出版の状態と再投入の規約を確かめたい                                            | [非同期処理の失敗時の再試行と回復](async-failure-recovery.md)                                                                                  |
| 上限に達した出版の ERROR に対応したい、手で再投入したい、自動の再投入を止めたい | [イベント出版の再投入のログイベント](../observability/runbook-event-publication-resubmission.md)                                               |
| 非同期のテストで待ちを書きたい                                                  | [ADR-080](../adr/ADR-080-wait-for-async-conditions-with-awaitility.md)、[バックエンドのテストコードの書き方](../backend/testing-code-style.md) |
| ShedLock の行や出版の表を使うテストを書きたい                                   | [バックエンドの DB テスト](../backend/testing-database.md)                                                                                     |
| PIT が遅い、止まる理由と設定の決定を知りたい                                    | [ADR-076](../adr/ADR-076-run-mutation-tests-per-change-and-tighten-coverage-gates.md)                                                          |

## 環境変数

次の 4 つの環境変数で挙動を切り替える。
意味の正文は[リスナーの方針](../observability/runbook-event-publication-resubmission.md#リスナーの方針)と ADR-079 にある。

| 変数                                                 | 意味                                      |
| ---------------------------------------------------- | ----------------------------------------- |
| `EVENT_PUBLICATION_RESUBMISSION_ENABLED`             | 定期の実行と Staleness Monitor を動かすか |
| `EVENT_PUBLICATION_RESUBMISSION_WAIT_AGE`            | 最初の待ち時間                            |
| `EVENT_PUBLICATION_RESUBMISSION_INTERVAL`            | 定期の実行の間隔                          |
| `EVENT_PUBLICATION_RESUBMISSION_IN_FLIGHT_STALENESS` | 処理中の出版を `FAILED` に戻すまでの時間  |

設定箇所は次のとおりである。

- **読み込み**：`backend/src/main/resources/application.yaml` の `event-publication.resubmission`。
- **ローカル**：`.env.example` から作る `.env`。値は `.env.example` を見る。
- **テスト**：`.env.test.example` から作る `.env.test`。値は `.env.test.example` を見る。
- **コンテナ**：`docker/compose.yml` が `.env` の値を渡す。
  4 つとも必須である。
- **本番**：このリポジトリに ECS のタスク定義はなく、値は未設定である。
  想定する値は ADR-079 にある。
