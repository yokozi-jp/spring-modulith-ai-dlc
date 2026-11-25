---
type: ADR
title: 'ADR-129: 処理済みのメッセージの表を一律に置かず、すべてのリスナーの冪等性を全件の結合テストで確かめる'
description: すべてのリスナーに処理済みのメッセージ ID の表を通す一律の規則を採らず、冪等の仕組みをリスナーごとに選び、すべての @ApplicationModuleListener に同じイベントを順に 2 回と同時に 2 回届ける全件の結合テストを全モジュールの義務にする決定。
tags: [adr, integration, async, idempotency, testing, backend]
---

# ADR-129: 処理済みのメッセージの表を一律に置かず、すべてのリスナーの冪等性を全件の結合テストで確かめる

## Status

Proposed

## Date

2026-11-25

## Context

同じイベントは、定期の再投入と手での再投入で 2 回以上届く（[ADR-079](ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)）。 古い出版を `FAILED` に戻す処理が処理中の出版を戻すと、同じイベントが同時に 2 回届くこともある。

[順序保証と冪等性](../integration/async-ordering-and-idempotency.md)は、業務のキーの一意制約か集約の楽観的ロックで足りるなら、処理済みのメッセージ ID の表を作らないとしていた。 しかし、リスナーが冪等であることを確かめる検査はなかった。 [Listener](../backend/class-roles/listener.md)のチェックリストの「呼ぶ CommandHandler を冪等にする」と「二回処理しても結果が変わらないことを確かめるテストがある」は［自分で点検］だった。 今の唯一のリスナー `OrderConfirmedListener` では、`OrderConfirmedListenerTest.secondDeliveryDoesNotChargeAgain` が順の 2 回目だけを確かめていた。

その `OrderConfirmedListener` が呼ぶ `ChargeOrderCommandHandler` は、決済記録をロックを取らない SELECT で先に読み、なければ決済代行を呼んでから決済記録を INSERT していた。 同時の 2 回目は先読みをすり抜け、INSERT の一意制約の違反で `ConflictException` を投げ、出版は `FAILED` に残って再投入で回復していた。 順の 2 回目だけを確かめるテストは、この失敗を見つけない。

[#339](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/339) では、`yokozi-jp/agent-software-factory` の受信の重複除去を調べた。 向こうは、すべての受信の処理に `processed_message` の表を通す一律の規則（`defineConsumer`）と、同じメッセージを 2 回届けて表の行数が変わらないことを確かめる全件の検査（`apps/api/test/consumer-check.ts`）を持つ。 調べた事実と出典は #339 のコメントにある。

## Decision

すべてのリスナーに処理済みのメッセージ ID の表を通す一律の規則を採らない。 冪等の仕組み（業務のキーの一意制約、集約の楽観的ロック、処理済みのメッセージ ID の表）は、リスナーごとに選ぶ。

すべての `@ApplicationModuleListener` の冪等性を、全件の結合テスト `ListenerIdempotencyTest` で確かめることを、全モジュールの義務にする。 リスナーを足すモジュールは、同じ変更でそのリスナーの入力例（前提の状態を作る準備とイベントの組）を足す。 テストは、同じ本文のイベントを順に 2 回と同時に 2 回届け、業務の表の行の中身と、外部システムの呼び出しの組の集合を、1 回だけ届けたときと比べる。 同時の 2 回は、1 回目をコミットの直前で止めてから 2 回目を流し、競合を必ず起こす。 テストが確かめる内容と失敗の条件は[非同期処理のテスト](../integration/async-testing.md)の「冪等性のテスト」に、テストで確かめられない範囲は[順序保証と冪等性](../integration/async-ordering-and-idempotency.md)の「全件の結合テストが確かめる範囲」にある。

同時の 2 回を通すため、リスナーが呼ぶ CommandHandler は、重複かどうかを DB が直列にする操作で判定する。 使う操作は、一意索引の `INSERT ... ON CONFLICT DO NOTHING`、`lock_no` を WHERE に書く `TableWriter.update`、`SELECT ... FOR UPDATE` の後の判定である。 衝突と 0 件の更新は、例外にせず処理済みとして正常に終える。 `ChargeOrderCommandHandler` は、決済記録の `INSERT ... ON CONFLICT DO NOTHING` で判定し、先読みを決済代行の二度目の呼び出しを省く最適化として残す。

処理済みのメッセージ ID の表を使うリスナーが現れたら、表を受け取るモジュールのスキーマに置き、主キーを `(tenant_public_id, イベントの本文が持つ ID, リスナーの ID)` にする。 リスナーの ID をキーに入れるのは、同じモジュールの 2 つのリスナーが同じイベントを受けるとき、片方の行がもう片方を止めないためである。 イベント出版のレジストリもリスナーごとに完了を記録するので、重複の単位をリスナーごとにそろえる。

## Consequences

### Positive

- 冪等でないリスナーを足すと、CI の結合テストが、そのリスナーの ID と違反の内容を挙げて落ちる。
- 入力例のないリスナーも落ちるため、新しいリスナーが検査から漏れない。 漏れなく集められるのは、`ClassRoleArchTest` の `eventListenersAreApplicationModuleListeners` と `applicationListenerIsNotImplemented` が、`@ApplicationModuleListener` 以外の受け方を禁じているためである。
- ロックを取らない SELECT で状態を見てから書くリスナーを、同時の 2 回の検査が毎回落とす。
- 受け取るモジュールごとに、処理済みの表、生成クラス、Repository、RLS、権限を足さずに済む。
- 同時の 2 回目が `FAILED` に残らなくなり、`ChargeOrderCommandHandler` で再投入による回復を待たずに済む。

### Negative

- 全件のテストは、テスト用の構成（コミットの直前で止める仕組み、外部システムの呼び出しの記録、業務の表をテナントごとに読む道具）を持ち、保守が要る。
- 結合テストの時間が、リスナーの数に比例して増える。
- 入力例が業務の脇の道（取り消した注文など）を通ると、検査が 1 行だけを見て通る。 この点は［自分で点検］とレビューに残る。
- 同時の 2 回は、1 回目がコミットの直前にいる一つの順だけを作る。ほかの順は、重複の判定の規約とレビューで守る。
- 外部システムに同じ引数で 2 回目を呼ぶことはテストが許すため、外部システムの側の重複は、渡す冪等性キーで守る。
- 冪等の仕組みがリスナーごとに違うため、レビューでは、リスナーごとに判定の書き方を読む。

### Neutral

- 全件のテストは同じ本文のイベントを 2 回出版するので、イベントの ID がなくても動き、[#312](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/312) に依存しない。
- 一律の表を見送る理由のうち、イベントの ID がないことは #312 で消えうる。ほかの理由（業務のキーのほうが強い、規約に合わない、消す処理が要る）は残る。
- 処理済みのメッセージ ID の表の行をいつ消すかは、表を使うリスナーが現れたときに決める。未完了の出版が残る間は消せない。
- 決済の流れが変わるため、決済の TLA+ のモデル（`formal-verify-order-payment`）を合わせて検査し直す。

## Alternatives Considered

### 選択肢1: すべてのリスナーに処理済みのメッセージ ID の表を通す

- **Description**：向こうの `defineConsumer` と同じく、すべてのリスナーの処理の初めに処理済みのメッセージ ID の表へ行を入れ、入ったときだけ業務の処理をする。
- **Pros**：冪等の仕組みが一つになり、リスナーごとに判定の書き方を読まずに済む。
- **Cons**：業務のキーのほうが守れる範囲が広い。`t_payment` の注文 ID の一意索引は、同じ注文について別々に出版されたイベントも止めるが、イベントの ID の表は同じ ID の再配信しか止めない。 向こうは共通のコードがモジュールの名前から表の名前を作るが、こちらは識別子を作る API を禁じる（`DatabaseConventionsArchTest.nameBuildingApisAreNotUsed`）ため、モジュールごとに表、生成クラス、Repository、RLS、権限を足すことになる。 こちらのイベントは ID を持たない（`OrderConfirmed(orderId, confirmedAt)`）。 ADR-079 では、上限に達した出版が残り、運用者がいつでも手で再投入できるため、処理済みの行を期限で消すと、その後の手での再投入を 2 回目と見分けられない。 向こうでも、外部の API を呼ぶ受信の処理は `defineConsumer` を使っておらず、一律になっていない。

### 選択肢2: 規約とレビューだけで守る

- **Description**：チェックリストの［自分で点検］のまま、リスナーごとのテストに任せる。
- **Pros**：テスト用の構成を作らずに済む。
- **Cons**：冪等でないリスナーを止める検査がない。`ChargeOrderCommandHandler` の同時の 2 回目の失敗は、この形のまま残っていた。

### 選択肢3: 全件のテストを順の 2 回だけにする

- **Description**：向こうの `consumer-check.ts` と同じく、同じイベントを順に 2 回届けるだけにする。
- **Pros**：コミットの直前で止める仕組みが要らず、テストが短い。
- **Cons**：ロックを取らない SELECT で状態を見てから書くリスナーは、順の 2 回目を通してしまう。古い出版を `FAILED` に戻す処理があるため、同時の 2 回目は起こりうる。

### 選択肢4: 表の行数だけを比べる

- **Description**：向こうの `snapshotRowCounts` と同じく、`count(*)` を比べる。
- **Pros**：テナントごとに行の中身を読む道具が要らない。
- **Cons**：在庫を足すような UPDATE の重複を見逃す。#339 で、`UPDATE ... SET qty = qty + 1` の後に `count(*)` が変わらないことを確かめた。

## References

- [#339 research: 受け取ったメッセージの重複を除くか](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/339)
- [#551 test(backend): すべての Listener に同じイベントを 2 回届け、2 回目が行を変えないことを確かめる](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/551)
- [ADR-054](ADR-054-detect-optimistic-lock-conflicts-by-update-count.md)、[ADR-055](ADR-055-set-db-time-limits-per-connection.md)、[ADR-079](ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)
- [順序保証と冪等性](../integration/async-ordering-and-idempotency.md)、[非同期処理のテスト](../integration/async-testing.md)、[Listener](../backend/class-roles/listener.md)、[PostgreSQL の排他制御](../database/postgresql-concurrency-control.md)
- [Idempotent Consumer（microservices.io）](https://microservices.io/patterns/communication-style/idempotent-consumer.html)
- [PostgreSQL INSERT（ON CONFLICT）](https://www.postgresql.org/docs/current/sql-insert.html)
