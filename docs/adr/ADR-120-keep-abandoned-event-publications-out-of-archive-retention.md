---
type: ADR
title: 'ADR-120: archive の表の保持期限を COMPLETED の行だけに掛け、ABANDONED の行は運用者が戻すか消すまで残す'
description: 再投入の回数の上限を超えて ABANDONED になったイベント出版は、運用者が扱うまで警報の対象として残す必要があるため、archive の表の保持期限（#542）を COMPLETED の行だけに掛ける決定。
tags: [adr, database, integration, spring-modulith, retention]
---

# ADR-120: archive の表の保持期限を COMPLETED の行だけに掛け、ABANDONED の行は運用者が戻すか消すまで残す

## Status

Proposed

## Date

2026-11-24

## Context

`completion-mode: archive` では、完了した出版は `modulith.event_publication_archive` に `COMPLETED` で移る。
再投入の回数の上限を超えて失敗した出版も、同じ表に `ABANDONED` で移る（[ADR-079](ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)）。
`ABANDONED` の出版は、運用者が原因を直して戻すか、再投入しないと決めて消すまで扱われていない。

archive の表には保持期限を設け、古い行を消す（[#542](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/542)、[テーブルごとの保持期限と個人データの有無の一覧](../database/table-retention-and-personal-data.md)）。
上限に達した出版の警報は、archive の `ABANDONED` の行の最古の経過時間の Gauge（`event.publication.failed.oldest.age{state=exhausted}`）で出す。

## Decision

archive の表の保持期限の削除は、`status = 'COMPLETED'` の行だけに掛ける。
`ABANDONED` の行は、運用者が Runbook の手順で戻すか消すまで残す（[イベント出版の再投入のログイベント](../observability/runbook-event-publication-resubmission.md)）。

## Consequences

### Positive

- 期限で消しても、扱っていない出版が警報ごと消えない。
- 上限に達した出版を戻すとき、イベントの本文（`serialized_event`）が残っている。

### Negative

- 運用者が扱わない `ABANDONED` の行は、期限なく残る。
  最古の経過時間の警報（1 日）が、扱われずに残った行を知らせる。
- 削除の SQL は `status` の条件を持つ必要があり、条件を落とすと `ABANDONED` の行も消える。

### Neutral

- Gauge は archive の `ABANDONED` の行を部分索引（`event_publication_archive_abandoned_idx`）で数えるため、`COMPLETED` の行が保持期限まで増えても集計は重くならない。

## Alternatives Considered

### 選択肢1: `ABANDONED` の行にも同じ保持期限を掛ける

- **Description**：`status` によらず、完了の時刻（`completion_date`）から期限を過ぎた行を消す。
- **Pros**：削除の条件が単純になり、表の大きさの上限が期限で決まる。
- **Cons**：期限までに扱わなかった出版が、記録も警報も残さずに消える。

### 選択肢2: `ABANDONED` の行を別の表へ移して保持する

- **Description**：上限に達した出版を、アプリが持つデッドレターの表へ写す。
- **Pros**：archive の表の削除の条件から `status` を外せる。
- **Cons**：Spring Modulith の表とは別の表、マイグレーション、写す処理が増え、戻す手順も変わる。

## References

- [ADR-079: 失敗したイベント出版を、ShedLock で 1 つのインスタンスに絞った定期のジョブで、リスナーごとの方針で再投入する](ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)
- [テーブルごとの保持期限と個人データの有無の一覧](../database/table-retention-and-personal-data.md)
- [イベント出版の再投入のログイベント](../observability/runbook-event-publication-resubmission.md)
- [issue #542](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/542)
