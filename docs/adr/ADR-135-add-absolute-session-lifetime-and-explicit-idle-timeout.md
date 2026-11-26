---
type: ADR
title: 'ADR-135: セッションに 8 時間の最長の期間を足し、無操作の 30 分を明示する'
description: セッションの無操作の期間（30 分）を設定に明示し、作成から数える最長の期間（8 時間）を自作の filter で切り、セッションの Cookie に有効期間を付けない決定。
tags: [adr, security, session, cookie, backend]
---

# ADR-135: セッションに 8 時間の最長の期間を足し、無操作の 30 分を明示する

## Status

Proposed

## Date

2026-11-26

## Context

[ADR-007](ADR-007-session-based-auth-with-oidc-pkce.md) は、認証状態をサーバー側のセッションで持つと決めたが、セッションの期間を決めていない。 無操作の期間は、`server.servlet.session.timeout` を書かないことで、Spring Boot の既定の 30 分になっていた。 最長の期間はなく、使い続ける限りセッションは切れない。 実測では、`creation_time` を 9 時間前に書き換えたセッションが 200 を返した。 盗まれたセッションの Cookie も、使い続ければ切れない（[issue #681](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/681)）。

Spring Session 4.2.0 と Spring Security 7.2.0 は、最長の期間の仕組みを持たない。 どちらも `getCreationTime()` を期限の判定に使わない。 `creation_time` は、セッションの ID を作り直しても変わらない（テナントを選ぶ前後で同じ値、実測）。

OWASP Session Management Cheat Sheet は、無操作の期限と最長の期限の両方を求める。 終日使う業務アプリの最長の期限には、4 時間から 8 時間を挙げる。

## Decision

- 無操作の期間を 30 分にし、`application.yaml` の `server.servlet.session.timeout: 30m` で明示する。Spring Boot は、この値を Spring Session JDBC の既定の期間として渡す。
- `spring.session.timeout` は書かない。両方を書くと、どちらの値が効くかが設定から読み取りにくいためである。
- 最長の期間を 8 時間にし、設定値 `app.session.max-lifetime`（`SessionLifetimeProperties`）に置く。
- `AbsoluteSessionTimeoutFilter` を `SecurityContextHolderFilter` の前で動かす。filter は、セッションの作成時刻（Spring Session の `getCreationTime()`、`modulith.spring_session.creation_time`）に最長の期間を足した時刻を、`Clock` の現在時刻と比べる。過ぎていれば、セッションを `invalidate()` する。
- 破棄した後のリクエストは未認証になり、既存の entry point が 401 を返す。SPA は 401 でログインへ移る。
- 破棄でセッションの行が消えるので、次のログインのセッションは新しい作成時刻から数える。古い作成時刻を引き継がない。
- 最長の期間は、認可の開始（ログインの前）にセッションを作った時刻から数える。ログインとテナントの選択はセッションの ID を作り直す（`changeSessionId`）が、`creation_time` を変えないので、期間は延びない。
- セッションの Cookie に `Max-Age` を付けず、ブラウザのセッションの Cookie にする。期間はサーバーが切る。
- ADR-007 が決めていないセッションの期間を、この ADR が足す。
- IdP に再認証を強制する指定（`prompt=login`、`max_age`）は、この ADR で決めない。

## Consequences

### Positive

- 盗まれたセッションの Cookie も、セッションの作成から 8 時間で使えなくなる。
- 無操作の期間が設定ファイルに現れ、Spring Boot の既定が変わっても変わらない。
- Cookie が永続にならないので、共有端末でブラウザを閉じると Cookie が消える。ただし、ブラウザが前回のセッションを復元する設定では残る。

### Negative

- 利用者は、操作を続けていても 8 時間ごとにログインし直す。操作の途中で 401 になることがある。
- IdP の SSO セッションが残っていれば、ログインし直すときに資格情報を求められない。資格情報なしで使い続けられる実際の上限は、IdP の SSO セッションの最長の期間（Keycloak と Cognito の設定）になる。
- filter を自作するので、Spring Session の版を上げるときは、`getCreationTime()` が ID の作り直しで変わらないことを確かめ直す。

### Neutral

- 最長の期間はログインではなく認可の開始から数えるので、ログインの時刻より数秒から数分早く切れる。
- 再認証を強制するかは、必要になったら別の ADR で決める。

## Alternatives Considered

### 選択肢1: Spring Security 7 の RequiredFactor.validDuration

- **Description**：認証の要素に有効期間を付け、古い認証の要求を拒否する。
- **Pros**：フレームワークの機能で、filter を自作しない。
- **Cons**：7.2.0 で測ると、OIDC のログインは `FACTOR_AUTHORIZATION_CODE` の権限を足さない。拒否は 401 ではなく 403 になる。DB のセッションと期限を過ぎた認証が残り、`TenantSelectionFilter` から見える。

### 選択肢2: SessionRepository を包む

- **Description**：Spring Session の `SessionRepository` を包み、期限を過ぎたセッションを読み出さない。
- **Pros**：セッションの読み出しのすべてに効く。
- **Cons**：`JdbcSession` の型が公開されていない。`FindByIndexNameSessionRepository` も転送する必要があり、filter より大きい。

### 選択肢3: maxInactiveInterval を残りの時間で縮める

- **Description**：無操作の期間を、最長の期間の残りより長くしない。
- **Pros**：Spring Session の期限切れの処理だけでセッションが切れる。
- **Cons**：縮める前に作った既存のセッションに効かないので、filter の検査も要る。

### 選択肢4: セッションの Cookie に Max-Age を付ける

- **Description**：Cookie の `Max-Age` を最長の期間に合わせる。
- **Pros**：ブラウザも期限を過ぎた Cookie を送らない。
- **Cons**：永続の Cookie になり、共有端末でブラウザを閉じても残る。期限はサーバーで切るので、守れるものが増えない。

## References

- [issue #681](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/681)
- [調査の記録：research/session-csrf-headers.md](https://github.com/yokozi-jp/spring-modulith-ai-dlc/blob/research/session-csrf-headers/research/session-csrf-headers.md)（[issue #345](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/345)）
- [OWASP Session Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html)（Idle Timeout と Absolute Timeout）
- [ADR-007](ADR-007-session-based-auth-with-oidc-pkce.md)
- [ADR-111](ADR-111-store-sessions-in-postgresql-until-user-growth-needs-redis.md)
- [ADR-119](ADR-119-harden-session-cookie-with-host-prefix.md)
- [APIの認証、セッション、権限](../web-api/authentication-and-session.md)
