---
type: Architecture
title: バックエンドの層の責務
description: 機能モジュール内の Domain、Application、Presentation、Infrastructure に何を置き、何に依存させないかを定める。クラスをどの層に置くか決めるとき、トランザクション境界や Adapter の分け方を決めるときに読む。
tags: [architecture, backend, onion-architecture]
---

# バックエンドの層の責務

機能モジュールの内部は、内側の Domain と Application、外側の Presentation と Infrastructure に分ける。
内側の層は外側の実装へ依存させない。
トランザクション境界は Application Service の public メソッドに置く。
層の間で許可する依存方向は [バックエンドアーキテクチャ](architecture.md) の「依存方向」に示す。

## Domain

業務状態と業務規則を置く内側の領域を **Domain** とする。

`domain.model` には集約、Entity、Value Object、Domain Event、業務不変条件を置く。

`domain.service` には、単一の集約へ自然に置けない業務規則だけを置く。

単なるデータ取得や処理の中継は Domain Service に置かない。

Domain は Spring、jOOQ、JPA、Jackson、Web、DB、外部 API クライアントへ依存させない。

Domain Model を API DTO や永続化 Record として兼用しない。

Domain Service は Spring の `@Service` を付けない通常の Java クラスとし、フレームワークから独立させる。

## Application

ユースケースの進行を担当する内側の領域を **Application** とする。

Application Service はモジュールルートの契約を実装し、Domain Model と Domain Service を組み合わせる。

トランザクション境界は Application Service の public メソッドへ `@Transactional` で明示する。

クラス単位の `@Transactional` は、public 以外のメソッドへ意図せず適用される可能性を避けるため使用しない。

Application は Presentation と Infrastructure の実装へ依存させない。

DB、メッセージブローカー、外部 API へのアクセスを抽象化する必要がある場合は、インタフェースを `application.port` に置く。

Infrastructure はそのインタフェースを実装する。

## Presentation

HTTP や UI からの入力と出力を扱う外側の領域を **Presentation** とする。

`presentation.web` には Spring MVC の Controller、Request、Response、Web 固有の変換処理を置く。

Controller は入力を検証して Application のユースケースを呼び出し、Domain Model をそのまま API レスポンスとして返さない。

Presentation は Infrastructure の実装へ直接依存させない。

## Infrastructure

DB、メッセージブローカー、外部 API との接続を扱う外側の領域を **Infrastructure** とする。

`infrastructure.persistence` には jOOQ を使う Repository 実装と、jOOQ の生成型を Domain 型へ変換する Mapper を置く。

`infrastructure.messaging` にはメッセージの受信、送信、シリアライズ、ブローカー固有の設定を置く。

`infrastructure.client` には外部 API クライアントと、外部形式を内部形式へ変換する処理を置く。

Presentation、Persistence、Messaging、外部 Client は別々の Adapter として扱い、相互に直接依存させない。

メッセージコンシューマーは HTTP と UI を扱う Presentation には含めず、transport 実装として `infrastructure.messaging` に置く。
