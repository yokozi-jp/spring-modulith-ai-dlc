---
type: ADR
title: 'ADR-121: イベント出版の完了を、リスナーのトランザクションの後の新しいトランザクションで記録する'
description: Spring Modulith 2.2 の既定の in-listener はリスナーのトランザクションの中で完了を記録し、モジュールのロールに modulith の表の権限を要するため、spring.modulith.events.completion を in-interceptor にして、ADR-094 のロールの分離を保つ決定。
tags: [adr, integration, async, spring-modulith, postgresql, security]
---

# ADR-121: イベント出版の完了を、リスナーのトランザクションの後の新しいトランザクションで記録する

## Status

Proposed

## Date

2026-11-24

## Context

Spring Modulith 2.2.0 は、出版の完了を既定でリスナーのトランザクションの中で、コミットの直前に記録する（[GH-1961](https://github.com/spring-projects/spring-modulith/issues/1961)、`spring.modulith.events.completion` の既定の `in-listener`）。
2.1 までは、リスナーのトランザクションが終わった後に、新しいトランザクションで記録していた（`in-interceptor`）。

リスナーのトランザクションは、トランザクションの初めにモジュールの DB ロールへ切り替えて動く（[ADR-094](ADR-094-switch-to-module-database-roles.md)）。
モジュールのロールは `modulith.event_publication` に `INSERT` しか持たない。
既定の `in-listener` のままでは、archive の表への写しが `permission denied for table event_publication_archive` で失敗し、決済のリスナーの出版が `FAILED` に残った（`OrderConfirmedListenerTest` で確認）。

## Decision

`spring.modulith.events.completion` を `in-interceptor` にし、出版の完了を、リスナーのトランザクションの後の新しいトランザクションでアプリのロールで記録する。
モジュールのロールに `modulith` の表の権限を足さない。

## Consequences

### Positive

- モジュールのロールは、他のモジュールの出版を読むことも書くこともできないまま残る（ADR-094）。
- 2.1 までの完了の記録の仕方と同じで、リスナーと再投入の前提（[ADR-079](ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)）が変わらない。

### Negative

- リスナーのトランザクションのコミットと完了の記録の間で落ちると、出版は完了にならず、もう一度配信される。
  リスナーの冪等性に頼る前提（#339、#551 の 2 回目の配信）は変わらない。
- `in-listener` が与える、リスナーの処理と完了の記録の原子性は得られない。

### Neutral

- Spring Modulith が `in-interceptor` を廃止するか既定の動きを変えたら、この判断を見直す。

## Alternatives Considered

### 選択肢1: 既定の `in-listener` にし、モジュールのロールに `modulith` の表の権限を足す

- **Description**：モジュールのロールに、`modulith.event_publication` の `SELECT`、`UPDATE`、`DELETE` と、`modulith.event_publication_archive` の `INSERT` を足す。
- **Pros**：リスナーの処理と完了の記録が同じトランザクションになり、2 回目の配信が減る。
- **Cons**：どのモジュールのロールも、他のモジュールの出版を読み書きできるようになり、ADR-094 のロールの分離を変える。

## References

- [ADR-079: 失敗したイベント出版を、ShedLock で 1 つのインスタンスに絞った定期のジョブで、リスナーごとの方針で再投入する](ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)
- [ADR-094: トランザクションの初めにモジュールの DB ロールへ切り替え、アプリロールを業務のスキーマから外す](ADR-094-switch-to-module-database-roles.md)
- [Spring Modulith GH-1961](https://github.com/spring-projects/spring-modulith/issues/1961)
- [CompletionTiming.java（2.2.0）](https://github.com/spring-projects/spring-modulith/blob/2.2.0/spring-modulith-events/spring-modulith-events-core/src/main/java/org/springframework/modulith/events/support/CompletionTiming.java)
