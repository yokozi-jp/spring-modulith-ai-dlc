---
type: ADR
title: 'ADR-122: Spring Modulith 2.2.0 を Spring Boot 4.1.2 のまま入れてから、Spring Boot 4.2.0 と Spring Cloud 2026.0.0 へ別の変更で上げる'
description: Spring Modulith 2.2.0 は Spring Boot 4.2.0 でビルドされているが、参照する API が Spring Boot 4.1.2 で解決しテストが通ることを根拠に先に 4.1.2 のまま入れ、その後の別の変更で Spring Cloud 2026.0.0 と一緒に Spring Boot 4.2.0 へ上げた決定。
tags: [adr, backend, spring-boot, spring-modulith, spring-cloud, dependencies]
---

# ADR-122: Spring Modulith 2.2.0 を Spring Boot 4.1.2 のまま入れてから、Spring Boot 4.2.0 と Spring Cloud 2026.0.0 へ別の変更で上げる

## Status

Proposed

## Date

2026-11-24

## Context

失敗したイベント出版の回数の上限を Spring Modulith 2.2 の `ABANDONED` で表すため、Spring Modulith を 2.2.0 へ上げる（[ADR-079](ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)、[#562](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/562)）。
Spring Modulith 2.2.0 は Spring Boot 4.2.0 でビルドされ、start.spring.io は 2.2 系を Spring Boot 4.2 系に対応づける。
このリポジトリの Spring Boot は 4.1.2（Spring Framework 7.0.10）である。

Spring Boot 4.2 へ上げるには、Spring Cloud（`spring-cloud-starter-circuitbreaker-resilience4j`、今は 2025.1.2）も 2026.0 系へ上げる必要がある。
Spring Boot 4.2.0 と Spring Cloud 2026.0.0 は、どちらも 2026-11-19 に Maven Central に公開された。
Spring Boot の minor の更新は、Spring Modulith と関係のない依存（Spring Framework、Spring Batch、Spring Session、Spring Security など）も上げる。

2.2.0 の jar が参照する Spring の 453 のメソッドとフィールドは、4.1.2 の classpath ですべて解決した。
バックエンドの全テスト（結合テストと `ResubmissionOptionsLearningTest` を含む）も 4.1.2 で通った。
2.2.0 のリリースノートにある再投入の修正（[GH-1925](https://github.com/spring-projects/spring-modulith/issues/1925)、[GH-1917](https://github.com/spring-projects/spring-modulith/issues/1917)、[GH-1908](https://github.com/spring-projects/spring-modulith/issues/1908)、[GH-1836](https://github.com/spring-projects/spring-modulith/issues/1836)）は 2.1.2 にすでに入っていた。
2.1.2 と 2.2.0 の `JdbcEventPublicationRepositoryV2` と `Staleness` の差は `ABANDONED` の追加だけで、外す回避はなかった。

## Decision

この ADR は Proposed の間に、#713 で Spring Boot を上げたので、Decision を実施の記録に書き直した。Context は、#562 の時点（Spring Boot 4.1.2、Spring Cloud 2025.1.2）の状況を書いている。

Spring Modulith 2.2.0 を Spring Boot 4.1.2 のまま入れる（[#562](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/562)）。
その後の別の変更（[#713](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/713)）で、Spring Boot を 4.2.0 へ、Spring Cloud を 2026.0.0 へ 1 つの変更で上げる。
これで Spring Modulith 2.2.0 は、start.spring.io が対応づける Spring Boot 4.2 系で動く。

### Spring Boot 4.2 への更新で確かめたもの

[リリースノート](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.2-Release-Notes)のうち、このリポジトリに関わる変更を、jar と設定で確かめた。

- `server.forward-headers-strategy` は非推奨になった。`server.request.header.forwarded.strategy: framework` に置き換えた。ALB の `X-Forwarded-*` の解釈は 4.1.2 と同じで、`Forwarded` と `X-Forwarded-Prefix` は無視される。キーを誤ると転送ヘッダを解釈しなくなるので、`ForwardedHeadersTest` が OIDC の `redirect_uri` で固定する。
- `server.tomcat.servlet.use-relative-redirects` の既定が `true` になった。ALB 経由の要求は転送ヘッダの filter が絶対 URL にし、直接の要求の相対の `Location` は同じオリジンへ解決される。
- Micrometer は、`LongTaskTimer` を既定で作らなくなった。同梱のダッシュボードと検証スキルは、その系列を使わない。
- Spring Framework 7.1 は空の `instance` に要求のパスを入れなくなった。[ADR-117](ADR-117-add-trace-id-and-omit-instance-in-problem-details.md) の予告どおりに、`ProblemDetailsConfig` の `setInstance(null)` を外した。応答に `instance` が出ないことは、`ApiErrorContractTest` と `ProblemDetailsTraceIdContractTest` が固定する。

残りの変更（`RestTemplate`、Pulsar、AMQP、LDAP、Redis、WebFlux、Buildpacks、Janino など）は、`backend/src` と `docker` に該当する設定や型の参照がない。

## Consequences

### Positive

- 依存の更新を、Spring Modulith と、Spring Boot と Spring Cloud の 2 つの変更に分けて確かめられた。
  振る舞いが変わったときに、どちらの更新が原因かを切り分けられる。
- Spring Modulith 2.2.0 と Spring Boot 4.2.0 の組が、start.spring.io の対応づけと一致する。

### Negative

- Spring Boot の更新は、Spring Batch、Spring Security、Spring Session の minor も上げる。
  4.1 の版で確かめた記述（[ADR-115](ADR-115-build-business-batches-with-spring-batch.md) の Spring Batch 6.0.6、`docs/web-api/authentication-and-session.md` の Spring Security 7.1.2 など）は、4.2 で確かめ直していない。
  各記述は、その時点の確認の記録として読む。

### Neutral

- [ADR-115](ADR-115-build-business-batches-with-spring-batch.md) の Spring Batch の版（Spring Boot 4.1.2 が管理する 6.0.6）など、4.1 を前提に確かめた他の ADR と docs の記述は、その時点の記録として残す。

## Alternatives Considered

### 選択肢1: Spring Modulith と同じ変更で Spring Boot 4.2 と Spring Cloud 2026.0 へ上げる

- **Description**：Spring Modulith 2.2.0、Spring Boot 4.2.0、Spring Cloud 2026.0.0 を 1 つの変更で入れる。
- **Pros**：start.spring.io が対応づける組で使える。
- **Cons**：Spring Boot の更新は Spring Modulith と関係のない依存も上げ、#562 の確かめ方と、振る舞いが変わったときの原因の切り分けが広がる。

### 選択肢2: Spring Boot 4.2 へ上げるまで Spring Modulith 2.1.2 のままにする

- **Description**：回数の上限を自前で数える仕組みを残し、Spring Boot 4.2 と一緒に 2.2 へ上げる。
- **Pros**：版の組が start.spring.io と一致したまま進む。
- **Cons**：自前で数える仕組み（SQL の式、ERROR の重複を避ける記録、回収）を、Spring Boot の更新が終わるまで保つ。

## References

- [ADR-079: 失敗したイベント出版を、ShedLock で 1 つのインスタンスに絞った定期のジョブで、リスナーごとの方針で再投入する](ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)
- [ADR-115: 業務のバッチを Spring Batch の Job で作り、1 つの期間を 1 つの JobInstance にして毎日追いつく](ADR-115-build-business-batches-with-spring-batch.md)
- [ADR-117: Problem Details に traceId を付け、instance を設定しない](ADR-117-add-trace-id-and-omit-instance-in-problem-details.md)
- [Spring Boot 4.2 のリリースノート](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.2-Release-Notes)
- [Spring Modulith 2.2.0 のリリースノート](https://github.com/spring-projects/spring-modulith/releases/tag/2.2.0)
- [Maven Central の spring-boot-dependencies](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/)
- [Maven Central の spring-cloud-dependencies](https://repo1.maven.org/maven2/org/springframework/cloud/spring-cloud-dependencies/)
- [issue #562](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/562)
