# ADR-008: application.yaml を単一にし、設定を外部から注入する

## Status

Accepted

## Date

2026-09-11

## Context

本 ADR は、既に実装済みの決定を遡って記録した（backfill）。

環境（ローカル / STG / 本番）ごとに設定値は変わる。
Spring Boot は `application-<profile>.yaml` によるプロファイル分割を標準で提供する。

一方で、DB パスワードや OIDC クライアントシークレットなどの秘密情報は
リポジトリ内の YAML には書けない。
したがって、いずれにせよ秘密情報は外部から注入する必要がある。

秘密を外部注入する仕組みが必須である以上、環境ごとに YAML を分けると、
「YAML に書く設定」と「外部注入する設定」の二系統を環境の数だけ管理することになり、
どこで何が上書きされるかが追いにくくなる。

## Decision

`application.yaml` を単一に保ち、環境ごとに変わる値と秘密情報は
すべて外部から注入する。

- 環境変数を参照し、`application.yaml` にデフォルト値を置かない。
  未設定なら起動時に失敗させ、設定漏れを早期に検知する。
- ローカルはリポジトリルートの `.env` で環境変数を与える
  （`spring.config.import: optional:file:../.env[.properties]`）。
- STG / 本番は ECS タスク定義の `environment` / `secrets` から、
  SSM Parameter Store または Secrets Manager の値を注入する。
- CI / CD は GitHub Actions の変数・シークレットから注入する。
- 環境差（例：`SESSION_COOKIE_SECURE`、`SPRINGDOC_ENABLED`、
  トレースのサンプリング率）は、プロファイル別 YAML ではなく環境変数の値で表現する。

## Consequences

### Positive

- 設定の定義が 1 ファイルに集約され、環境間の YAML ドリフトが起きない。
- 秘密情報がリポジトリに一切入らない。
- 必須値が未設定なら起動に失敗するため、設定漏れを本番で踏む前に検知できる。
- 「YAML の設定」と「外部注入の秘密」の二系統を環境ごとに持たずに済む。

### Negative

- すべての必須環境変数を、どの環境でも漏れなく供給する必要がある。
- 供給すべき環境変数の一覧（契約）を、別途明示・維持する必要がある。

### Neutral

- 環境変数の契約は `.env.example` で共有し、注入元は環境ごとに異なる
  （ローカル `.env`、本番 Parameter Store / Secrets Manager、CI GitHub Actions）。
- 型付き `@ConfigurationProperties` + Bean Validation による起動時検証は、
  この方針と両立し、外部注入値の妥当性確認として併用できる。

## Alternatives Considered

### Alternative 1: プロファイル別 YAML（`application-prod.yaml` など）

- 説明：環境ごとに YAML を分ける。
- Pros：非秘密の環境差を YAML で宣言的に表現できる。
- Cons：秘密は結局外部注入が要るため二系統管理になり、
  環境の数だけ設定の重複と上書き関係の追跡コストが増える。

### Alternative 2: Spring Cloud Config などの集中設定サーバ

- 説明：外部の設定配信サービスを使う。
- Pros：多数サービス・多環境での集中管理に向く。
- Cons：現在の規模には運用コストが過大で、追加の可用性依存を持ち込む。

## References

- `backend/src/main/resources/application.yaml`
- `.env.example`
- [`.github/workflows/production-cd.yml.example`](../../.github/workflows/production-cd.yml.example)
