---
type: ADR
title: 'ADR-122: Spring Modulith 2.2.0 を Spring Boot 4.1.2 のまま入れ、Spring Boot 4.2 と Spring Cloud 2026.0 への更新を別の変更にする'
description: Spring Modulith 2.2.0 は Spring Boot 4.2.0 でビルドされているが、参照する API が Spring Boot 4.1.2 で解決しテストが通ることを根拠に 4.1.2 のまま入れ、Spring Cloud 2026.0 を伴う Spring Boot 4.2 への更新を別の変更にする決定。
tags: [adr, backend, spring-boot, spring-modulith, spring-cloud, dependencies]
---

# ADR-122: Spring Modulith 2.2.0 を Spring Boot 4.1.2 のまま入れ、Spring Boot 4.2 と Spring Cloud 2026.0 への更新を別の変更にする

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

Spring Modulith 2.2.0 を Spring Boot 4.1.2 のまま入れる。
Spring Boot を 4.2 系へ、Spring Cloud を 2026.0 系へ上げる変更は、この変更の後に 1 つの変更で行う。
上げる条件（Spring Boot 4.2.0 と Spring Cloud 2026.0.0 の公開）は満たされている。

## Consequences

### Positive

- 依存の更新を、Spring Modulith と、Spring Boot と Spring Cloud の 2 つの変更に分けて確かめられる。
  振る舞いが変わったときに、どちらの更新が原因かを切り分けられる。

### Negative

- Spring Modulith と Spring Boot の組み合わせは、start.spring.io が対応づける組から外れる。
  2.2 系の patch が Spring Boot 4.2 だけの API を使うと、起動か実行の時に失敗する。
  Spring Boot 4.2 へ上げるまでの間、Dependabot の Spring Modulith の更新で全テストが通らなければ、その patch を入れずに Spring Boot を先に上げる。

### Neutral

- Spring Boot 4.2 へ上げるときは、Spring Boot と Spring Framework の版を書いた他の ADR と docs（[ADR-115](ADR-115-build-business-batches-with-spring-batch.md) の Spring Batch の版など）も確かめる。

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
- [Spring Modulith 2.2.0 のリリースノート](https://github.com/spring-projects/spring-modulith/releases/tag/2.2.0)
- [Maven Central の spring-boot-dependencies](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/)
- [Maven Central の spring-cloud-dependencies](https://repo1.maven.org/maven2/org/springframework/cloud/spring-cloud-dependencies/)
- [issue #562](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/562)
