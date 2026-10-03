---
type: Architecture
title: バックエンドの層の責務
description: 機能モジュール内の Domain、Application、Presentation、Infrastructure に置くクラスの役割と、トランザクション境界の置き場所を説明する。クラスをどの層のどの役割にするか決めるとき、トランザクション境界やイベントの発行と受信の置き場所を確かめるときに読む。
tags: [architecture, backend, onion-architecture]
---

# バックエンドの層の責務

機能モジュールの内部は、内側の Domain と Application、外側の Presentation と Infrastructure に分ける。
内側の層は外側の実装へ依存させない。
トランザクション境界は、Application の CommandHandler の `handle`、QueryService の public メソッド、Listener の `on` に置く。
層の間で許可する依存方向は [バックエンドアーキテクチャ](architecture.md) の「依存方向」に示し、役割の決定理由は [ADR-050](../adr/ADR-050-define-backend-class-roles-and-naming.md) に示す。

## Domain

業務状態と業務規則を置く内側の領域を **Domain** とする。

`domain.model` には次の型を置く。

- **[集約](class-roles/aggregate.md)**：業務上の一貫性の単位。状態は業務の操作を表すメソッドで変更する。
- **[Entity](class-roles/entity.md)**：集約の中で識別子を持つ型。
- **[値オブジェクト](class-roles/value-object.md)**：識別子を持たず、値と不変条件を表す record または enum。
- **[`<Aggregate>Repository`](class-roles/repository.md)**：集約を保存し、取り出すインタフェース。
- **[`<ExternalSystem>`](class-roles/external-system-interface.md)**：決済などの外部システムを、ドメインの語彙で表すインタフェース。

`domain.model` は Spring、jOOQ、JPA、Jackson に依存させない。
Domain Model を API の Request と Response や、永続化の Record として兼用しない。

業務規則は、まず値オブジェクトか Entity に置く。
`domain.service` の **[Domain Service](class-roles/domain-service.md)** は、次の三つの規則だけを置く。

- 複数の集約にまたがる規則。
- どの集約にも自然に属さない計算。
- Repository を使って確かめる規則。

Domain Service には Spring の `@Service` を付ける。
`@Service` は Domain が依存してよい唯一の Spring の型である。
Domain Service は Repository を使ってよく、イベントの発行、外部システムの呼び出し、ログ出力は行わない。

## Application

ユースケースの進行を担当する内側の領域を **Application** とする。
`application` の `@Service` は、次の三つの役割のどれかにする。

- **[`<UseCase>CommandHandler`](class-roles/command-handler.md)**：状態を変えるユースケースを一つ実行する。`<UseCase>Command` を受け取り、集約と Domain Service を組み合わせ、Repository で保存し、`<UseCase>Result` を返す。イベントは `ApplicationEventPublisher` で発行する。
- **[`<Feature>QueryService`](class-roles/query-service.md)**：モジュールルートの [`<Feature>Queries`](class-roles/feature-queries.md) を実装し、Repository で読んだ集約をルートの record（[参照の結果](class-roles/query-result.md)）に変換する。
- **[`<Event>Listener`](class-roles/listener.md)**：他モジュールの[イベント](class-roles/event.md)を受信し、自モジュールの CommandHandler をちょうど一つ呼ぶ。

[`<UseCase>Command`](class-roles/command.md) と [`<UseCase>Result`](class-roles/result.md) は、Java の標準型だけを持つ record として `application` に置く。

CommandHandler は別の CommandHandler を呼ばない。

トランザクション境界は次のメソッドに置く。

- CommandHandler の `handle` に `@Transactional` を付ける。
- QueryService の public メソッドに `@Transactional(readOnly = true)` を付ける。
- Listener の `on` に `@ApplicationModuleListener` を付ける。このアノテーションは新しいトランザクションを開く。

クラス単位の `@Transactional` は付けない。

Application は Presentation と Infrastructure の実装へ依存させない。

## Presentation

HTTP からの入力と出力を扱う外側の領域を **Presentation** とする。

`presentation.web` には次の型を置く。

- **[`<Aggregate>Controller`](class-roles/controller.md)**：集約ごとの Spring MVC の Controller。Request を Command に変換して CommandHandler を呼び、参照は `<Feature>Queries` を呼ぶ。
- **[`<UseCase>Request`](class-roles/request.md)**：リクエストボディを受けるユースケースの入力の record。`toCommand()` で Command に変換する。
- **[`<QueryResult>Response`](class-roles/response.md)**：参照の結果の応答の record。`from(...)` でルートの record から作る。

Presentation は Domain と Infrastructure に依存させない。

## Infrastructure

DB と外部システムとの接続を扱う外側の領域を **Infrastructure** とする。

- **`infrastructure.persistence`**：`<Aggregate>Repository` を jOOQ で実装し、jOOQ の生成型と Domain の型の変換も持つ [`Jooq<Aggregate>Repository`](class-roles/jooq-repository.md) を置く。
- **`infrastructure.client`**：`<ExternalSystem>` を実装する [`<ExternalSystem>Client`](class-roles/external-client.md) を置く。

Persistence、外部 Client、Presentation は別々の Adapter として扱い、互いに依存させない。
Infrastructure は Application、Domain Service、モジュールルートの型に依存させない。
