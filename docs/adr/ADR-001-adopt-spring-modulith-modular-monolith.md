# ADR-001: Spring Modulith によるモジュラーモノリス

## Status

Accepted

## Date

2026-09-11

## Context

本 ADR は、既に実装済みの決定を遡って記録した（backfill）。

初期段階のプロダクトで、単一デプロイの運用の軽さを保ちつつ、
機能間の境界を明確にし、将来のサービス分割余地も残したい。
マイクロサービスは分散トランザクション、ネットワーク境界、
運用基盤の複雑さを初期から抱える。
素のモノリスはパッケージ境界が時間とともに腐敗し、
どの機能がどこに依存しているかを静的に保証できない。

## Decision

Spring Modulith を用いたモジュラーモノリスを採用する。

- `com.example.demo` の直接サブパッケージをアプリケーションモジュールとする。
- 起動時に `spring.modulith.runtime.verification-enabled` を有効化し、
  モジュール間の依存違反があれば起動を失敗させる。
- モジュール間の連携は、公開 API とアプリケーションイベントに限定する。
- イベント出版はレジストリ（jdbc、`completion-mode: archive`）で追跡し、
  外部ブローカーへの externalization は当面無効にする。

## Consequences

### Positive

- 単一デプロイ・単一トランザクションの単純さを保てる。
- モジュール境界を `ApplicationModules.verify()` と ArchUnit で静的検証できる。
- 境界を保ったまま、将来個別サービスへ抽出しやすい。

### Negative

- Modulith のパッケージ規約とモジュール設計の学習が要る。
- 境界維持の規律（内部参照の禁止、イベント設計）を継続的に守る必要がある。

### Neutral

- イベント出版レジストリを DB に持つため、対応するテーブルを Liquibase で管理する。
- 複数インスタンス運用時の再配信は起動時に自動実行しない設定にしている。

## Alternatives Considered

### Alternative 1: マイクロサービス

- 説明：機能ごとに独立デプロイするサービス群。
- Pros：独立スケール・独立デプロイ。
- Cons：初期段階には分散の複雑さ（ネットワーク、整合性、運用）が過大。

### Alternative 2: 素のモノリス（境界規約なし）

- 説明：単一アプリケーションで、モジュール検証を持たない。
- Pros：最も単純で導入が速い。
- Cons：境界が腐敗し、依存関係を静的に保証できない。

### Alternative 3: Gradle マルチモジュール

- 説明：ビルド単位で分割する。
- Pros：コンパイル時に境界を分離できる。
- Cons：実行時のモジュール検証やイベント基盤は得られず、ビルド構成が重くなる。

## References

- [`docs/architecture/package-by-feature-onion-handoff.md`](../architecture/package-by-feature-onion-handoff.md)
- `backend/src/main/resources/application.yaml`（`spring.modulith.*`）
- `backend/src/test/java/com/example/demo/architecture/ApplicationModuleArchitectureTest.java`
