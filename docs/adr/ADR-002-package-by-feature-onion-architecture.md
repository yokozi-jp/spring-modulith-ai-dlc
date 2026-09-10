# ADR-002: package by feature とオニオンアーキテクチャ

## Status

Accepted

## Date

2026-09-11

## Context

本 ADR は、既に実装済み・確定済みの方針を遡って記録した（backfill）。

モジュール内部の構造を定めないと、technical layer 単位
（controller / service / repository を横断パッケージに集約する構成）に流れ、
機能をまたぐ依存が生まれやすい。
ドメインを技術詳細（Web、DB、外部 API）から独立させ、
依存を内向きに保ちたい。

## Decision

最上位の分割に package by feature を用い、各機能パッケージの内部に
オニオンアーキテクチャを適用する。

- 機能モジュールは `com.example.demo.<feature>`。
- モジュールルートには他モジュールへ公開する契約だけを置く。
- 内部は `domain` / `application` / `presentation` / `infrastructure` に分ける。
- 外周命名は Hexagonal の `adapter.in/out` ではなく、
  Onion / Clean で一般的な `presentation` と `infrastructure` を使う。
- 依存は内向き（presentation / infrastructure → application → domain）に限定し、
  業務モジュール実装後に `Architectures.onionArchitecture()` で強制する。

## Consequences

### Positive

- ドメインが技術詳細から独立し、テストと変更が容易になる。
- 機能単位で凝集し、機能をまたぐ結合を抑えられる。
- Modulith の内部パッケージ規約と自然に噛み合う。

### Negative

- 層とパッケージの規約を全機能で一貫させる規律が要る。
- 小さい機能では層が過剰に見えることがある。

### Neutral

- Persistence / Messaging / 外部 Client は別 Adapter として登録し、
  外周同士の直接依存を禁止する。
- 空パッケージは先に作らず、役割を持つ型が生じた時点で追加する。

## Alternatives Considered

### Alternative 1: package by layer（技術レイヤー分割）

- 説明：controller / service / repository を横断パッケージに集約する。
- Pros：小規模では素直。
- Cons：機能をまたぐ依存が生まれ、Modulith の機能モジュール分割と衝突する。

### Alternative 2: Hexagonal の adapter.in / adapter.out 命名

- 説明：Ports and Adapters の命名を使う。
- Pros：入出力の対称性が明確。
- Cons：Web API 主入口の本プロジェクトでは Onion 命名のほうが通りが良く、
  presentation を独立させたい意図と合う。

### Alternative 3: jMolecules の Onion 規則

- 説明：アノテーションで Ring を識別する。
- Pros：DDD 戦術パターンと統合できる。
- Cons：独立した Presentation Ring がなく、外周内部の直接依存を
  Onion 規則だけでは禁止できない。ArchUnit 標準のほうが所属漏れも検出できる。

## References

- [`docs/architecture/package-by-feature-onion-handoff.md`](../architecture/package-by-feature-onion-handoff.md)
- [`docs/architecture/archunit-additional-rules.md`](../architecture/archunit-additional-rules.md)
