---
type: ADR
title: 'ADR-061: ヘルスチェックは liveness と readiness だけを未認証で許可する'
description: Actuator の health のうち liveness と readiness の probe だけを未認証で許可し、/actuator/health のルートはログイン済みでも拒否する決定。
tags: [adr, backend, security, observability]
---

# ADR-061: ヘルスチェックは liveness と readiness だけを未認証で許可する

## Status

Proposed

## Date

2026-10-06

## Context

認証付きの ZAP による DAST（PR #126、[ADR-056](ADR-056-run-authenticated-dast-with-zap-in-ci.md)）で、未認証の `GET /actuator/health` が全体の status と、liveness や readiness の group 名を返すと分かった（issue #124）。
従来の `SecurityConfig` は `HealthEndpoint` 全体を `permitAll` にしており、`/actuator/health` のルートと配下のすべてのパスが未認証で開いていた。

ヘルスチェックを呼ぶのは、ALB、ECS、コンテナの healthcheck、監視といった内部の部品だけである。
利用者も管理者も health を画面や操作で使わない。
既存の呼び出し元（`backend/Dockerfile`、`docker/compose.yml`、`docker/compose-test.yml`、`Taskfile.yml`）は、どれも `/actuator/health/readiness` だけを呼ぶ。

[ADR-007](ADR-007-session-based-auth-with-oidc-pkce.md) は、未認証で許可する経路をヘルスチェックに限っている。
[ADR-014](ADR-014-use-same-origin-spa-security-boundary.md) は公開するパスを挙げているが、`/actuator/**` を本番の公開 origin でどう扱うかを決めていない。
本番のインフラ定義はまだ無く（`infrastructure/` は空で、AWS の docs は ADR-045 で削除した）、ALB や CloudFront の振り分けもこれから作る。

Actuator の Web に公開する endpoint は `health` だけで、`show-details: never` により詳細も返さない。
メトリクス、トレース、ログは OTLP で push しており（Micrometer の OTLP registry と OpenTelemetry SDK、[ADR-015](ADR-015-structure-and-protect-observability-data.md)）、収集のために HTTP の endpoint を開く必要がない。

## Decision

アプリは `/actuator/health/liveness` と `/actuator/health/readiness` だけを未認証で許可する。
`/actuator/health` のルートと、それ以外の health 配下のパスは、ログイン済みの利用者にも許可しない（`denyAll`）。
probe のパスは `SecurityConfig` に明示し、拒否する側は `EndpointRequest.to(HealthEndpoint.class)` で解決する。

本番の公開 origin（CloudFront など）は、`/actuator/**` をバックエンドへ振り分けない。
ALB のターゲットグループのヘルスチェックは、内部の経路で readiness か liveness を直接呼ぶ。
この二つは、本番のインフラを作るときに満たす要件とする。

Actuator の Web に公開する endpoint は `health` だけとし、`show-details: never` を保つ。
メトリクス、トレース、ログは OTLP で push するため、`metrics` や `prometheus` を HTTP で公開しない。
`info`、`loggers`、`env`、`heapdump` などを将来公開するときは、`management.server.port` で管理用のポートを分けることを前提に、別の ADR で判断する。

## Consequences

### Positive

- 未認証でもログイン済みでも、集約した health の status と group 名を外から読めない。
- 既存の呼び出し元は readiness だけを使うため、変更が要らない。
- 公開 origin が `/actuator/**` を振り分けないため、アプリの設定を誤っても外部から probe 以外の Actuator に届きにくい。

### Negative

- probe のパスを明示したため、`management.endpoints.web.base-path` を変えると `SecurityConfig` も直す必要がある。
  直し忘れると probe が拒否され、コンテナの healthcheck が失敗するので、公開側には倒れない。
- 外部の監視サービスが `/actuator/health` のルートを見る運用はできない。

### Neutral

- 公開 origin の振り分けと ALB のヘルスチェックの設定は、インフラを作る作業で満たす。
- Accept を指定しない未認証の `GET /actuator/health` は、`/api/**` と同じ Problem Details の entry point を既定として通り、401 と `WWW-Authenticate: Session realm="demo"`（[ADR-059](ADR-059-send-a-session-challenge-in-www-authenticate-on-401.md)）を返す。
  ログイン済みの要求は 403 になる。

## Alternatives Considered

### 通常の OIDC ログインを要求する

- **Description**：health をほかの画面と同じく `authenticated()` にする。
- **Pros**：設定が短く、未認証の露出は無くなる。
- **Cons**：health は人向けの資源ではない。ログインした利用者なら誰でも内部の状態を読めてしまう。

### 管理者ロールで認可する

- **Description**：管理者ロールを持つ利用者だけに `/actuator/health` を許可する。
- **Pros**：運用者がブラウザから状態を確かめられる。
- **Cons**：ロールによる認可はまだ無い。health を使うのは内部の部品だけで、人が読む必要がない。

### 今すぐ管理用のポートを分ける

- **Description**：`management.server.port` で Actuator を別のポートに移し、外部に公開しない。
- **Pros**：Actuator 全体を公開の経路から切り離せる。
- **Cons**：本番の経路がまだ無く、compose、Dockerfile、Taskfile の healthcheck の変更まで差分が広がる。今は見送り、ほかの endpoint を公開するときに判断する。

### 露出を受け入れる

- **Description**：`HealthEndpoint` 全体を `permitAll` のままにする。
- **Pros**：変更が要らない。
- **Cons**：DAST の指摘が残り、内部の構成（group 名）を未認証の相手に返し続ける。

## References

- [issue #124](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/124)
- [ADR-007](ADR-007-session-based-auth-with-oidc-pkce.md)（未認証の経路をヘルスチェックに限る方針と矛盾しない）
- [ADR-014](ADR-014-use-same-origin-spa-security-boundary.md)
- [ADR-015](ADR-015-structure-and-protect-observability-data.md)
- [ADR-056](ADR-056-run-authenticated-dast-with-zap-in-ci.md)
- [ADR-059](ADR-059-send-a-session-challenge-in-www-authenticate-on-401.md)
- [APIの失敗のログレベルと監視](../web-api/logging-and-monitoring.md)
- [Spring Boot: Endpoints](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html)
- [Spring Boot: Health Groups](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.health.groups)
- `backend/src/main/java/com/example/demo/SecurityConfig.java`
- `backend/src/test/java/com/example/demo/ActuatorHealthSecurityTest.java`
