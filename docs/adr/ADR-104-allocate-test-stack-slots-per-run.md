---
type: ADR
title: 'ADR-104: compose-test を使う実行ごとに slot を割り当て、worktree をまたいで同時に流す'
description: task test、task e2e、task e2e-up、task scan-dast が実行ごとに 4 つの slot から 1 つを取り、slot ごとの Compose project と固定のポートで compose-test を起動する決定。使用中の判定はコンテナを正本にし、割り当ての排他は git の共通ディレクトリの flock で取る。
tags: [adr, testing, e2e, compose, worktree, tooling]
---

# ADR-104: compose-test を使う実行ごとに slot を割り当て、worktree をまたいで同時に流す

## Status

Proposed

## Date

2026-11-19

## Context

エージェントは git worktree ごとに作業し、複数の worktree で同時に Task を実行する。
`docker/compose-test.yml`（compose-test）を使う Task は、`task test`、`task e2e`、`task e2e-up`（`/verify-demo`）、`task scan-dast` である。
この ADR の前は、これらの Task が compose-test の同じホストのポート（PostgreSQL 5433、Redis 6380、Keycloak 8081、決済代行の WireMock 8082）のうち必要なものを使い、`task e2e` はさらに Vite preview の 5173 と backend の 8080 を使っていた。
`task test` と `task e2e` は同じ Compose project（`spring-modulith-test`）を使い、`task test` は開始時と終了時にこの project を `down --volumes` で消していた。
そのため、ある worktree で push すると、別の worktree の E2E や `/verify-demo` の stack が消えた。
`task scan-dast` は project の名前（`spring-modulith-dast`）が違うが、同じホストのポートを使うため、ほかの Task と同時には起動できなかった。

実行ごとにポートを変えるには、既存の判断による制約がある。
Keycloak の realm は、redirect URI、web origin、ログアウト後の URI を origin ごとに登録し、Vite は登録した origin から移らないよう `strictPort` で待ち受ける（[ADR-033](ADR-033-pin-vite-dev-origin-and-source-proxy-port.md)、[ADR-057](ADR-057-adopt-playwright-for-e2e-tests.md)）。
RFC 9700 は、ネイティブアプリの localhost の redirect URI を除き、redirect URI を完全一致で比べることを求め、Keycloak は redirect URI のポートをワイルドカードで扱えない。
Keycloak の issuer（`KC_HOSTNAME`）と SPA の origin は、起動の前に決まっている必要がある。
ホストで動く Gradle の migration とシーダー、`/verify-demo` の `sql`、Playwright が呼ぶ WireMock の管理 API も、ホストから見たポートで接続する。

slot の数は、[Issue #418](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/418) の計測から 4 にした。
8 コアのマシンで 4 本の `task e2e` が全て通り、Keycloak の起動は最悪 93 秒だった（healthcheck が失敗にするのは約 140 秒）。

[ADR-111](ADR-111-store-sessions-in-postgresql-until-user-growth-needs-redis.md) で Redis を外したため、Proposed の間にslot のポートと置き換える変数から Redis を除いた。

## Decision

compose-test を使う Task は、実行ごとに slot を 1 つ取り、slot ごとの Compose project と固定のポートで compose-test を起動する。
Issue #418 で決めたことは次のとおりである。

- **対象**：`task test`、`task e2e`、`task e2e-up`、`task scan-dast` のすべてが slot を取る。
  現行の対象は [compose-test の slot](../tooling/test-slots.md#対象の-task) にある。
- **単位**：slot は実行ごとに取り、その実行の片付けで返す。
  worktree には固定しない。
- **使用中の判定**：slot n は Compose project `spring-modulith-test-<n>` に当たり、その project のコンテナが 1 つでもあれば使用中とする。
  持ち主は Compose が付けるラベル `com.docker.compose.project.working_dir` で示し、その親のディレクトリ（worktree）が消えていれば、コンテナを回収して slot を取ってよい。
  PID や記録のファイルは持たない。
- **割り当ての排他**：git の共通ディレクトリの `test-slot.lock` で `flock` を取り、空きを探して最初のコンテナ（`docker compose create postgres`）を作るまでだけ持つ。
- **ポート**：slot 0 は従来のポートのままにする。
  slot n（1 から 3）は `20000 + n × 1000 +` 従来のポートの下 3 桁にする（slot 1 は web 21173、backend 21080、Keycloak 21081、WireMock 21082、PostgreSQL 21433）。
  OS の ephemeral port（32768 から 60999）と、開発用のポート（18080、8090、5432 など）を避けるためである。
  ポートを別のプロセスが使っている slot は飛ばす。
- **realm**：test realm（`docker/keycloak/realm.json`）に、4 つの slot の web の origin（5173、21173、22173、23173）を、redirect URI、web origin、ログアウト後の URI として静的に並べる。
  origin の数が slot の数と一致することを、`task test-slot-test` で確かめる。
- **空きがないとき**：最大 15 分待ち、30 秒ごとに、待っていることと slot を持つ worktree を出し、上限を超えたら失敗にする。
  黙って失敗せず、黙ってほかの stack も使わない。
- **CI**：`CI` が空でなければ、待たずにいつも slot 0 を使う。
  1 つの job は 1 台のマシンで動くためである。
- **ADR**：この ADR を起こし、ADR-057 の「E2E は 5173 と 8080 を使う」だけを、slot ごとの固定のポートに改める。
  ADR-057 は Proposed のため、[ADR の運用ルール](conventions.md)の「ライフサイクル」に従い、`Amended by` を使わず、ADR-057 の Decision と Consequences を直接書き直す。
  ADR-033 は変えず、preview の `strictPort` も slot ごとの固定のポートで残す。

slot の値は、環境変数と slot の env ファイル（`build/test-slot/slot-<n>.env`）で渡す。
slot の env ファイルは `.env.test` の写しで、ホストから見たポートを持つ変数（`DB_PORT`、`OIDC_ISSUER_URI`、`PAYMENT_GATEWAY_BASE_URL`）だけを置き換える。
compose-test の backend はコンテナの待ち受けのポートを `SERVER_PORT: "8080"` に固定し、`.env.test` の `SERVER_PORT` は、ホストから見た backend のポートだけを表すようにする。

`task test` は、`be-verify-migrations` と `be-test` の間で PostgreSQL のコンテナを消さずに止め、最後の `down` まで slot を持つ。
コンテナを消すと slot が空き、ほかの実行がその slot を取った後に、最後の `down` がその実行の project を消すためである。

`task scan-dast` が自分で使う 4173、18081、18082 は固定のままにする。
ZAP の plan は、Keycloak のポートを slot の値に置き換えた写し（`build/dast/plans`）を使う。

## Consequences

### Positive

- 4 つまでの実行を、worktree をまたいで同時に流せる。
  push の `task test` が、ほかの worktree の E2E や `/verify-demo` の stack を消さない。
- 利用者は slot もポートも選ばない。
  1 本だけ流すときは slot 0、つまり従来のポートで動く。
- ポートが `127.0.0.1` で応答する slot は飛ばすため、開発用の Keycloak（`127.0.0.1:8080`）が動いていても、slot 1 以降で E2E を流せる。
- 使用中の状態の正本がコンテナだけなので、異常終了で記録のファイルが古いまま残ることがない。
  ロックは、プロセスが終わると OS が外す。
- Task は slot を取ると、web、backend、Keycloak、WireMock の URL と Compose project を出すので、エージェントは出力から自分の stack を知れる。

### Negative

- 中断（Ctrl-C、kill）した実行はコンテナを残し、手で片付けるか worktree を消すまで slot を持ち続ける。
  この ADR の前は、次の実行が開始時に前回の環境を消していた。
  今は、同じ worktree の次の実行も別の slot を取る。
- 5 本目からの実行は、空くまで最大 15 分止まる。
- slot を増やすときは、realm の origin も同じ数だけ足す必要がある。
  `task test-slot-test` が、数の不一致を失敗にする。
- 2 つの `task scan-dast` は同時に流せない。
  自分で使う 4173、18081、18082 が固定のためである。
- ロックは clone ごとの git の共通ディレクトリにあるため、同じホストの別の clone とは排他しない。
- `task e2e-collect-logs` を単独で実行すると、環境に `COMPOSE_PROJECT_NAME` がなければ従来の project のログを読む。
  `/verify-demo` の `stack.sh` は slot の記録を持ち、そこから環境を復元して呼ぶ。

### Neutral

- `task dev`（`docker/compose.yml`）は対象外にする。
  同時に動かしたときの負荷を測っていない。
- 開発用の Keycloak も同じ `realm.json` を取り込むため、取り込み直した後は slot 1 から 3 の origin も登録される。
- Backend CI は compose を直接呼び、従来の project の名前（`spring-modulith-test`）を使う。
  `task test-deps-leftover-check` は、名前に `spring-modulith-test` を含むコンテナと volume を探し、どちらの project の残りも検出する。
- 4 という上限は Issue #418 の計測による。
  負荷が変われば、ポートの式と realm の origin を合わせて見直す。

## Alternatives Considered

### 選択肢1: Docker が選ぶ空きのポート

- **Description**：`127.0.0.1::5432` のようにホストのポートを Docker に選ばせ、起動の後に `docker compose port` で読む。
- **Pros**：ポートの式と realm の列挙が要らず、slot の数の上限もない。
- **Cons**：Keycloak の issuer と SPA の origin は、起動の前に決まっている必要がある。
  ホストの Gradle、`/verify-demo` の `sql`、WireMock の管理 API の全員が、起動の後にポートを問い合わせることになるため採らない。

### 選択肢2: PID や記録のファイルによる管理

- **Description**：取った slot と持ち主の PID をファイルに記録し、片付けで消す。
- **Pros**：Docker に問い合わせずに空きを判断できる。
- **Cons**：異常終了するとファイルが古いまま残り、実物のコンテナと食い違うため採らない。

### 選択肢3: 起動のたびに生成する realm

- **Description**：起動の前に、その slot の origin を差し込んだ realm を生成する（Issue #418 の本文の案）。
- **Pros**：realm に、使っていない slot の origin を並べずに済む。
- **Cons**：4 つの origin を静的に並べれば足り、生成の仕組みは要らないため採らない。

### 選択肢4: worktree に固定する slot

- **Description**：worktree ごとに slot を決め、post-checkout hook と `.env.local` で記録する（Issue #418 の本文が挙げた案）。
- **Pros**：同じ worktree の stack が、いつも同じポートになる。
- **Cons**：同じ worktree の `/verify-demo` と pre-push の `task test` が、同じ slot を取り合うため採らない。

## References

- [Issue #418](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/418)、[実装の前に決めたこと](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/418#issuecomment-7011608210)
- [ADR-033: Vite 開発オリジンを固定し proxy ポートを単一ソース化する](ADR-033-pin-vite-dev-origin-and-source-proxy-port.md)
- [ADR-057: E2E テストに Playwright と Chromium を採用し、テストデータを公開 API で作る](ADR-057-adopt-playwright-for-e2e-tests.md)（E2E のポートを slot ごとの固定のポートに書き直した）
- [compose-test の slot](../tooling/test-slots.md)
- [RFC 9700, Best Current Practice for OAuth 2.0 Security, Section 2.1](https://www.rfc-editor.org/rfc/rfc9700.html#section-2.1)
- [Docker, Specify a project name](https://docs.docker.com/compose/how-tos/project-name/)
- [Docker, Using profiles with Compose](https://docs.docker.com/compose/how-tos/profiles/)
