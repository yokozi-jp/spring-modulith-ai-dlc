---
type: ADR
title: 'ADR-056: CI で OWASP ZAP による認証付きの DAST を実行する'
description: vp preview の同一オリジンに対し、OWASP ZAP の Automation Framework で認証付きの passive scan を PR ごとに、active scan を週 1 回と手動実行で行い、検出は Code Scanning に送ってスキャンの成立だけをゲートにする決定。
tags: [adr, security, ci, dast, zap]
---

# ADR-056: CI で OWASP ZAP による認証付きの DAST を実行する

## Status

Proposed

## Date

2026-10-04

## Context

いまのセキュリティ検査は静的解析と依存の検査だけで、Semgrep、Trivy、zizmor が担っている。
動いているアプリに HTTP を送って検査する DAST はない。
このため、ヘッダー、Cookie 属性、エラー応答の設定の退行と、注入系の脆弱性は、実行時の挙動としては確かめていない。

業務 API はまだ一つもなく、コミット済みの OpenAPI 契約は `paths: {}` である。
未認証で到達できるのは health、OAuth2 のログイン、`/error`、API 文書だけで、それ以外は認証が要る。
このため、未認証のスキャンではほとんどの応答が 401 か Keycloak への 302 になり、検査できる範囲が狭い。

CSRF は Spring Security の `csrf.spa()` で、`__Host-XSRF-TOKEN` Cookie（ADR-066）の値を `X-XSRF-TOKEN` Header で送り返させる。
GET、HEAD、TRACE、OPTIONS 以外はすべてトークンが要り、除外パスはない。
ZAP の anti-CSRF 処理はフォームのトークンしか扱わず、この Header を付けられない。

backend は SPA を配信しない。
[ADR-014](ADR-014-use-same-origin-spa-security-boundary.md) は、SPA と backend を一つの HTTPS origin で公開し、SPA の CSP は入口が付けると決めている。
本番の配信点はまだないので、同一オリジンを再現できるのは Vite の proxy だけである。
`vp build` のあとの `vp preview` は dev server の proxy を引き継ぎ、本番用の CSP を返す。

開発者は dev 環境（5173、8080、18080）を動かしたまま検査したい。
CI の既存ジョブ（`hadolint.yml` の backend イメージの検査）は、`docker/compose-test.yml` と `.env.test` で依存と backend を起動しており、この手順は流用できる。

## Decision

OWASP ZAP を DAST に採用する。
イメージは `zaproxy/zap-stable` を tag と digest の両方で固定し、`docker run --network host` で起動する。
設定は ZAP Automation Framework の plan YAML で書き、`docker/zap/` に置く。

passive scan と active scan の plan を分ける。
どちらの plan も、context、認証、ログイン状態の判定、セッション、ユーザーの設定を同じ内容にそろえる。

スキャン対象は `vp preview` で起動した同一オリジンの `http://localhost:4173` とする。
preview は proxy で backend の `http://localhost:18081` へ転送する。
backend は `.env.test` を使い、`SERVER_PORT=18081` だけを環境変数で上書きする。
依存は `docker/compose-test.yml` の `backend-smoke` profile で起動し、Keycloak（`127.0.0.1:8081`）はスキャン範囲から外す。
redirect_uri が `http://localhost:4173` から組み立てられるので、`docker/keycloak/realm.json` の client に redirect URI と post logout URI を追加する。

認証付きでスキャンする。
認証は browser-based authentication（`firefox-headless`）で、ログインは `http://localhost:4173/oauth2/authorization/web` から始め、`test-admin` を使う。
ログイン状態は `GET /api/missing` の poll で判定する（未認証なら 401、認証済みなら 404）。
セッション管理は自動検出を使い、うまくいかなければ `APP_SESSION` Cookie を指定する。
`/logout` はスキャン範囲から外す。

`__Host-XSRF-TOKEN` Cookie の値を `X-XSRF-TOKEN` Header に写す httpsender script を作り、リポジトリで管理する。
スキャンの前に、認証した状態の `GET /api/missing` と `POST /api/missing` がどちらも 404 になることを確かめる。
POST が 403 なら script が働いていないので、タスクを失敗させる。

検出があってもタスクとジョブは失敗させない。
失敗させるのは、起動、ログイン、CSRF の前提確認の失敗のように、スキャンが成立しない場合に限る。
検出は SARIF で Code Scanning に送り、HTML と JSON のレポートを artifact に残す。
Informational の検出は SARIF から外して Code Scanning に送らず、artifact のレポートにだけ残す。
PR の passive scan のジョブは必須チェックにするが、保証するのは完走だけである。
検出を blocking に切り替える基準は決めず、検出が出たときに都度判断する。

ADR-014 で受容した差による指摘だけを `alertFilter` で除外し、除外ごとに理由と根拠の ADR をコメントで書く。
`X-Forwarded-Host` 関連の指摘は先回りして除外せず、出たときに調べる。

PR では、関連パスが変わったときに passive scan を実行する。
active scan は、週 1 回の `schedule` と `workflow_dispatch` で実行する。
schedule を使うのはこのリポジトリで初めてである。
active scan は時間がかかり、攻撃の通信を送るので、PR ごとには実行しない。
週 1 回の実行は、コードが変わらなくても、依存やイメージの更新による退行を捕まえる。
ジョブの失敗は GitHub の通常の失敗通知で気づく。

ローカルと CI は同じタスク（`task scan-dast` と `task scan-dast-active`）を呼ぶ。
タスクは依存、backend、preview、ZAP を起動し、失敗したときも後片付けをする。
専用のポート（4173、18081、18082、5433、6380、8081）を使い、開始前に空いていることを確かめる。
18082 は ZAP 自身の proxy のポートで、ZAP の既定の 8080 は dev の Keycloak と衝突するので変える。
これらのポートは dev 環境と重ならないので、dev 環境と同時に実行できる。

## Consequences

### Positive

- ヘッダー、Cookie 属性、エラー応答の設定の退行を、実行時の挙動として PR ごとに検査できる。
- SPA の本番用 CSP と backend の応答を、本番と同じ同一オリジンの形で一度に検査できる。
- 認証と CSRF を通るので、active scan が認証の必要なパスと unsafe メソッドに届く。
- ローカルと CI が同じタスクなので、CI の結果を手元で再現できる。

### Negative

- schedule の実行の失敗通知は、cron を最後に編集した利用者に届く。
- 公開リポジトリでは、60 日間活動がないと GitHub が schedule の workflow を無効にする。
- CI の実行時間が増える（PR ごとの passive scan と、週 1 回の active scan）。
- ZAP のイメージの digest を更新し続ける必要がある。
- HTTP で動かすために出る指摘を除外するので、HTTP のときだけ現れる問題は見えない。

### Neutral

- dev の Keycloak は volume に realm を保持しているので、realm.json の変更を反映するには `task keycloak-reimport` が要る。
  DAST は使い捨ての Keycloak を使うので不要である。
- 業務 API ができるまで、DAST の環境ではマイグレーションを実行しない。
- AJAX spider と OpenAPI 定義の取り込みは、業務 API と画面が増えてから検討する。
- push の trigger は置かない。
  PR の SARIF は PR に表示され、既定ブランチの Security タブは週 1 回の active scan（passive の検査も含む）で更新される。

## Alternatives Considered

### 選択肢1: backend 単体に zap-baseline.py を当てる

- **Description**：backend の `http://localhost:8080` を、ZAP の baseline スクリプトで検査する。
- **Pros**：設定が少なく、preview や認証の準備が要らない。
- **Cons**：SPA の CSP を検査できない。baseline スクリプトは SARIF を出力しない。

### 選択肢2: 未認証のスキャンだけにする

- **Description**：ログインせずにスキャンする。
- **Pros**：Keycloak と browser-based authentication の設定が要らない。
- **Cons**：ほとんどのパスが 401 か 302 になり、検査できる範囲がほぼない。

### 選択肢3: realm を変えずに `vp preview --port 5173` で起動する

- **Description**：realm に登録済みの 5173 で preview を起動する。
- **Pros**：realm.json を変えずに済む。
- **Cons**：dev server の 5173 と衝突し、dev 環境と同時に実行できない。

### 選択肢4: active scan を PR ごとに実行する

- **Description**：PR のたびに active scan まで行う。
- **Pros**：注入系の退行を PR の時点で見つけられる。
- **Cons**：時間がかかり、PR ごとに攻撃の通信を送る。

### 選択肢5: Medium 以上の検出でジョブを失敗させる

- **Description**：risk が Medium 以上の検出があれば、ジョブを失敗させる。
- **Pros**：検出が放置されにくい。
- **Cons**：まだ baseline がなく、何を受容するかを決める前にゲートにすると、検出の調査より除外の追加が先に進みやすい。

### 選択肢6: `zaproxy/action-af` を使う

- **Description**：ZAP の公式 GitHub Action で plan を実行する。
- **Pros**：ZAP の起動を action に任せられる。
- **Cons**：CI とローカルの手順が分かれ、CI が同じタスクを呼ぶ方針に反する。

### 選択肢7: CSRF の script を後回しにする

- **Description**：状態を変える業務 API が増えるまで、httpsender script を作らない。
- **Pros**：script とその前提確認を保守しなくて済む。
- **Cons**：unsafe メソッドのリクエストがすべて 403 になり、active scan がその先を検査できない。

## References

- [ADR-007: セッションベース認証と OIDC Authorization Code + PKCE](ADR-007-session-based-auth-with-oidc-pkce.md)
- [ADR-014: SPA とバックエンドを同一オリジンで公開する](ADR-014-use-same-origin-spa-security-boundary.md)
- [ADR-017: トランクベース開発とリポジトリ保護を採用する](ADR-017-adopt-trunk-based-repository-governance.md)
- [ZAP Browser Based Authentication](https://www.zaproxy.org/docs/desktop/addons/authentication-helper/browser-auth/)
- [ZAP Automation Framework Environment](https://www.zaproxy.org/docs/desktop/addons/automation-framework/environment/)
- [ZAP Verification Strategies](https://www.zaproxy.org/docs/desktop/start/features/authstrategies/)
- [ZAP Anti CSRF Handling](https://www.zaproxy.org/docs/desktop/start/features/anticsrf/)
- [ZAP Alert Filter Automation Framework Support](https://www.zaproxy.org/docs/desktop/addons/alert-filters/automation/)
- [ZAP SARIF JSON Report](https://www.zaproxy.org/docs/desktop/addons/report-generation/report-sarif-json/)
- [ZAP Automation Framework exitStatus Job](https://www.zaproxy.org/docs/desktop/addons/automation-framework/job-exitstatus/)
