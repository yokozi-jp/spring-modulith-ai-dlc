---
type: ADR
title: 'ADR-137: イベントの変更を @Nullable の component の追加だけにし、過去の版の JSON を今の型で読めることを fixture のテストで確かめる'
description: 保存したイベント出版をデプロイの後のコードで読めるよう、イベントの変更を @Nullable の component の追加だけにし、版を fixture のファイル名だけに持たせ、@ApplicationModuleListener が受ける型の過去の版の JSON を EventContractTest で今の型に読ませる判断と、送る側の events のパッケージ、本文の版、スキーマ、レビューだけ、古い形の読み替えを取り込まない理由。
tags: [adr, backend, integration, async, spring-modulith, testing]
---

# ADR-137: イベントの変更を @Nullable の component の追加だけにし、過去の版の JSON を今の型で読めることを fixture のテストで確かめる

## Status

Proposed

## Date

2026-11-26

## Context

Spring Modulith は、`@ApplicationModuleListener` が受けるイベントを、Spring Boot が `spring.jackson` で構成した `JsonMapper` で JSON にして `modulith.event_publication` に保存する。
列 `event_type` にはクラスの完全な名前を、列 `serialized_event` には JSON を書く。
失敗した出版は [ADR-079](ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md) のジョブが再投入し、そのときデプロイの後のコードが `event_type` のクラスを探して JSON を読む。
そのため、イベントの record を変えると、変える前に保存した出版を読めなくなることがある。
DB の列の型は、この互換を保証しない。

Issue #312 の調査と #560 の使い捨てのテストで、今の構成の読み方を確かめた。

- 足した component と、名前を変えた component は `null` で読め、例外にならない。足したプリミティブは `0` になる。
- 型を変えた component だけが例外になる。
- JSON にあって record にない項目は捨てる。
- 型の名前かパッケージを変えると、Spring Modulith は WARN を出して出版を読み出しの結果から落とし、ERROR も出ない（#312 で 2.1.2 の、#560 で 2.2.0 の `JdbcEventPublicationRepositoryV2` のソースで確かめた）。
  ADR-079 は、これを Negative の「イベントの型の名前を変えた出版」として認め、[失敗したイベント出版の再投入の Runbook](../observability/runbook-event-publication-resubmission.md) は警報で後から見つける手順を持つ。

壊し方の多くは例外を出さず、値を黙って `null` か `0` にするか、出版を落とす。
これまで、イベントの変え方の規則も、変更をマージの前に止める検査もなかった。
[クラスの役割：イベント](../backend/class-roles/event.md) は、イベントに「専用のテストは作らない」としていた。

Issue #312 では、`yokozi-jp/agent-software-factory` の互換の規則（変更は省略できる項目の追加だけ）と、過去の版の fixture のテスト（同リポジトリの ADR 0046 と `docs/async/event-contract.md`）を一部取り込むと決めた。
同リポジトリは送る側に events のパッケージを別に置き、全イベントに共通の `version` を持たせる。
この 2 点は取り込まない（選択肢1、選択肢2）。

## Decision

イベントを変えるときは、`@Nullable` の component を足すことだけをする。
プリミティブの component は足さない。
component の削除、名前の変更、型の変更と、record の名前とパッケージの変更はしない。
そうしたいときは新しい型のイベントを足し、古い型の出版が DB に残る間は、古い型と、それを受けるリスナーと fixture を残す。
規則の本文は[クラスの役割：イベント](../backend/class-roles/event.md)の「必須の記述」と「対応するテスト」に書く。

`@ApplicationModuleListener` が受ける型ごとに、過去の各版の JSON を `backend/src/test/resources/events/<型の完全な名前>.v<n>.json` に置く。
版は fixture のファイル名だけが持ち、イベントの本文に版の component を持たせない。
イベントを足すか変える変更では、同じ変更で次の版の fixture を足し、過去の fixture は書き換えも削除もしない。
最初の fixture は、#377 で `OrderConfirmed` にテナントを足した後の形の `com.example.demo.ordering.OrderConfirmed.v1.json` にする。

`EventContractTest` を、この規則を守るための検査にする。
テストは、main の `@ApplicationModuleListener` が受ける型の各 fixture を、`application.yaml` の `spring.jackson` で構成した本番と同じ `EventSerializer` で今の型に読む。
読めないとき、読んで書き直した JSON が fixture と違うとき、版の fixture が欠けるときに失敗し、どの fixture の何が悪いかと直し方を示す。
検査する規則の一覧と、テストが見つけない変更は、[クラスの役割：イベント](../backend/class-roles/event.md)の「対応するテスト」と `EventContractTest` が持つ。
`EventContractViolationTest` は、わざと違反する型と fixture で、各規則が違反を見つけることを確かめる。

送る側に events のパッケージを別に置かない。
イベントは今のとおりモジュールルートに置き、受信側が送信側のルートに依存する。
依存の向きに逆らう受け手が要るときは、依存の向きの判断として別の ADR で決める。

## Consequences

### Positive

- 型の名前とパッケージの変更、component の削除と名前の変更、必須の component の追加、`spring.jackson` の変更を、変える前の版の fixture が残る限り、マージの前にテストが止める。
  どれも本番では例外を出さずに値を変えるか出版を落とすため、これまでは警報が出るまで気付けなかった。
  component の型の変更は、fixture の値が新しい型で読めないか、書き直すと変わるときに止める。
- 版をファイル名が持つので、record に版の component を足さずに済み、受信側は版で分岐しない。
- 失敗のメッセージが fixture と直し方を示すので、変更した人は規則の文書を探さずに直せる。

### Negative

- 守れるのは JSON の形の互換だけである。
  同じ名前のまま単位や意味を変える変更は検出しない。
- テストは各版の fixture の一つの値で読むため、component の型の変更を見逃すことがある。
  `long` を `int` に変えても、fixture の値が `100` なら読んで書き直せて通り、DB に `5000000000` の出版が残っていればデプロイの後に読めない。
  範囲のある型の component には、fixture に境界の値（狭い型に収まらない数、許す最も長い文字列）を入れて補う。
- 過去の fixture の書き換えと削除は、`EventContractTest` では止められない。
  型と一緒に fixture の名前を変えるか消すと、テストは通り、ADR-079 の Negative の状態が起きうる。
  pre-push の `task test-weakening` が `backend/src/test/resources/events/` の下のファイルの変更、削除、rename を警告し、[人が必ず読むPull Request](../pull-request/review-request-and-merge.md#人が必ず読むpull-request)のレビューで止める。
  警告は止めず、`TESTS_ACK=1` で抑えられるため、最後はレビューに頼る。
- 古い型を新しい型へ移したとき、古い型、それを受けるリスナー、fixture をいつ消すかは決めていない。
  古い型の出版が DB に残る間は消せないが、残っていないことを確かめる手順はない。
  消す時期は運用の判断として、必要になったときに決める。
- イベントを変えるたびに fixture が 1 つ増える。

### Neutral

- 規則は `@ApplicationModuleListener` が受ける型だけに掛かる。
  リスナーのない型の出版は保存されないため、互換も要らない。
- 本番に出す前なので、`OrderConfirmed` の `v1` より前の形の互換は求めない。

## Alternatives Considered

### 選択肢1: 送る側に events のパッケージを別に置く

- **Description**：同リポジトリのように、モジュールごとにイベントだけのパッケージを置き、送り手も受け手もそこに依存する。
- **Pros**：依存の向きに逆らう受け手を、送り手を直さずに足せる。
- **Cons**：Spring Modulith はモジュールの間のすべての型の依存で循環を検出するため、逆向きのイベントの型を受けるだけで循環になる（#312 の使い捨てのテスト）。
  許すには、モジュールごとにイベントだけのモジュールを足し、`ClassRoleArchTest.moduleRootTypesAreRecordsEnumsOrQueries` などのルートの規則を作り直すことになる。
  今のモジュールの間のイベントは `OrderConfirmed`（`ordering` から `payment`）だけで、逆向きの受け手はない。

### 選択肢2: イベントの本文に版を持たせる

- **Description**：全イベントに共通の `version` の component を持たせ、fixture の版と照合する。
- **Pros**：保存した JSON だけを見て版が分かる。
- **Cons**：受信側は版で分岐しないため、版の役目は fixture の照合だけで、ファイル名で足りる。
  本文に持たせると、すべての record に同じ component が増え、発行する CommandHandler が正しい版を詰める責任も増える。
  版の番号と本文の `version` の照合という検査も要る。

### 選択肢3: JSON Schema かスキーマレジストリで新旧の形を比べる

- **Description**：イベントのスキーマを JSON Schema で書くかスキーマレジストリに登録し、後方互換の規則で新旧のスキーマを比べる。
- **Pros**：互換の判定を道具に任せられ、外部のブローカーへ送るメッセージにも同じ仕組みを使える。
- **Cons**：イベントは同じプロセスの中で Jackson が読み書きするため、実際の形は record と `spring.jackson` の設定で決まる。
  スキーマを別に書くと record と二重になり、`spring.jackson` の変更は比べるスキーマに現れない。
  同じプロセスの中のイベントには AsyncAPI も使わず（[非同期処理の設計文書](../integration/async-design-documents.md)）、レジストリを動かす基盤もない。
  本番と同じ `EventSerializer` で過去の JSON を読むほうが、デプロイの後の読み方を直接確かめられる。

### 選択肢4: 規則を docs に書き、レビューだけで守る

- **Description**：変更の規則を docs に書き、検査を置かない。
- **Pros**：テストと fixture を保つ手間がない。
- **Cons**：壊し方の多くは例外を出さないため、レビューで見落とすと、本番で値が `null` になるか出版が落ちるまで分からない。
  `spring.jackson` の変更はイベントの差分に現れず、レビューでイベントへの影響に気付きにくい。

### 選択肢5: 古い形の JSON を今の型へ読み替える変換を足す

- **Description**：Jackson の変換（upcaster）を版ごとに書き、古い JSON を今の型へ読み替えて、削除や名前の変更を許す。
- **Pros**：新しい型を足さずに、既存の型の形を変えられる。
- **Cons**：本文に版がないため、どの変換を当てるかを JSON の形から推し量ることになる。
  Spring Modulith の `EventSerializer` に変換を差し込む実装が要り、変換そのものの検査も要る。
  型の名前の変更は、`event_type` のクラスが見つからず変換の前に落ちるため、変換では扱えない。
  イベントは `OrderConfirmed` だけで、新しい型を足すほうが変更は小さい。

## References

- Issue #560（この判断を実装する）、#312（取り込むものの調査）
- [ADR-079: 失敗したイベント出版の再投入](ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)：Negative の「イベントの型の名前を変えた出版」
- [ADR-050: バックエンドのクラスの役割と命名](ADR-050-define-backend-class-roles-and-naming.md)
- [クラスの役割：イベント](../backend/class-roles/event.md)
- [失敗したイベント出版の再投入の Runbook](../observability/runbook-event-publication-resubmission.md)の「イベントの型の名前を変えた出版」
- [Spring Modulith Working with Application Events](https://docs.spring.io/spring-modulith/reference/events.html)
- [JdbcEventPublicationRepositoryV2.java（2.2.0）](https://github.com/spring-projects/spring-modulith/blob/2.2.0/spring-modulith-events/spring-modulith-events-jdbc/src/main/java/org/springframework/modulith/events/jdbc/JdbcEventPublicationRepositoryV2.java)
