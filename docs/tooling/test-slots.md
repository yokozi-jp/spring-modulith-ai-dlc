---
type: Reference
title: compose-test の slot
description: compose-test を使う Task が実行ごとに取る slot の割り当て、slot ごとのポートと Compose project、渡す環境変数と env ファイル、空きがないときの待ち、片付け、既知の制限をまとめたリファレンス。複数の worktree で task test、task e2e、task e2e-up、task scan-dast を流すとき、自分の slot の URL やポートを確かめるとき、slot の待ちや残ったコンテナを調べるときに読む。
tags: [reference, testing, compose, worktree]
---

# compose-test の slot

`docker/compose-test.yml` を使う Task は、実行ごとに 0 から 3 の slot を 1 つ取り、片付けで返す。
slot ごとに Compose project とホストのポートが分かれるため、複数の worktree で同時に実行しても、互いの stack を消さない。
割り当ては `tooling/test-slot/test-slot.sh` が行い、利用者は slot もポートも指定しない。
決定の理由は [ADR-104](../adr/ADR-104-allocate-test-stack-slots-per-run.md) にある。

## 対象の Task

- `task test`
- `task e2e` と、その中で呼ぶ `task e2e-up`
- 単独で実行する `task e2e-up`（`/verify-demo` が呼ぶ）
- `task scan-dast` と `task scan-dast-active`

`task dev` と、開発用の `docker/compose.yml` は対象外である。

## 割り当ての規則

- slot n の Compose project は `spring-modulith-test-<n>` である。
  Task は `COMPOSE_PROJECT_NAME` を export し、`docker/compose-test.yml` の `name:`（`spring-modulith-test`）を上書きする。
- slot 0 から順に空きを探し、最初に空いていた slot を取る。
- project のコンテナが 1 つでもあれば、その slot は使用中である。
  使用中かどうかはコンテナだけで判断し、PID や記録のファイルを持たない。
- コンテナの持ち主は、Compose が付けるラベル `com.docker.compose.project.working_dir` の親のディレクトリ（worktree のルート）である。
  そのディレクトリが無ければ、project のコンテナ、volume、network を消して、その slot を取る。
- project のコンテナが無くても、slot のポートのどれかが `127.0.0.1` で応答すれば、その slot を飛ばす。
  たとえば開発用の Keycloak（`127.0.0.1:8080`）が動いていれば、slot 0 を飛ばす。
- 空きを探し始めてから最初のコンテナ（`docker compose create postgres`）を作り終えるまで、git の共通ディレクトリの `test-slot.lock` を `flock` で持つ。
  コンテナができた後はロックを返し、ほかの実行からはその slot が使用中に見える。
- `CI` が空でなければ、空きを探さず、待たずに slot 0 を使う。
- `TEST_SLOT` に番号があれば、空きを確かめず、コンテナも作らずにその slot を使う。
  `task e2e` は、中で呼ぶ `task e2e-up` に自分の slot をこの変数で渡す。

## ポート

slot 0 は従来のポートのままである。
slot n（1 から 3）のポートは、`20000 + n × 1000 + slot 0 のポートの下 3 桁` である。

| 用途                | 環境変数             | slot 0 | slot 1 | slot 2 | slot 3 |
| ------------------- | -------------------- | ------ | ------ | ------ | ------ |
| web（Vite preview） | `E2E_WEB_PORT`       | 5173   | 21173  | 22173  | 23173  |
| backend             | `TEST_BACKEND_PORT`  | 8080   | 21080  | 22080  | 23080  |
| Keycloak            | `TEST_KEYCLOAK_PORT` | 8081   | 21081  | 22081  | 23081  |
| 決済代行の WireMock | `TEST_WIREMOCK_PORT` | 8082   | 21082  | 22082  | 23082  |
| PostgreSQL          | `TEST_PG_PORT`       | 5433   | 21433  | 22433  | 23433  |

`bash tooling/test-slot/test-slot.sh ports <n>` が、slot n の値を出す。
`task scan-dast` が自分で使う 4173（preview）、18081（backend）、18082（ZAP の proxy）は、slot によらず固定である。

## 渡す値

Task は slot を取ると、次の 1 行を標準エラーに出す。

```text
slot 1: web http://localhost:21173、backend http://localhost:21080、Keycloak http://127.0.0.1:21081、WireMock http://127.0.0.1:21082（Compose project spring-modulith-test-1）
```

続けて、次の環境変数を export する。

- `TEST_SLOT`、`COMPOSE_PROJECT_NAME`、表の 6 つのポート。
- `TEST_ENV_FILE`：slot の env ファイル（`build/test-slot/slot-<n>.env`）。
- `SERVER_PORT`（`TEST_BACKEND_PORT` と同じ値）、`OIDC_ISSUER_URI`、`PAYMENT_GATEWAY_BASE_URL`（後の 2 つは slot の env ファイルと同じ値）。
  `task e2e`、`task e2e-up`、`task scan-dast` だけが渡す（`acquire` の第 2 引数 `web`）。
  `task test` は渡さない。環境変数は Spring の env ファイルの import より優先され、`PAYMENT_GATEWAY_BASE_URL` などが slot の WireMock を指して、Gradle のテストが起動したコンテナの接続先とずれるため。
  `vp preview` と Playwright は、ルートの `.env.test` よりこれらの環境変数を優先して読む。

slot の env ファイルは、基にする env ファイル（Task の `TEST_ENV_FILE`、既定は `.env.test`）の写しである。
ホストから見たポートを持つ次の 3 つの変数だけを、slot の値に置き換える。
基にする env ファイルは書き換えない。

| 変数                       | slot の値                                                      |
| -------------------------- | -------------------------------------------------------------- |
| `DB_PORT`                  | `TEST_PG_PORT`                                                 |
| `OIDC_ISSUER_URI`          | `http://127.0.0.1:<TEST_KEYCLOAK_PORT>/realms/spring-modulith` |
| `PAYMENT_GATEWAY_BASE_URL` | `http://127.0.0.1:<TEST_WIREMOCK_PORT>`                        |

`task be-migrate` と `task be-seed` は `COMPOSE_ENV_FILE` で、`task be-verify-migrations` と `task be-test` は `TEST_ENV_FILE` で、slot の env ファイルを受け取る。
compose-test の backend（`e2e` profile）は `.env.test` を `env_file` で読み、`SERVER_PORT` をコンテナの待ち受けのポート 8080 に、`OIDC_ISSUER_URI` を slot の Keycloak の URL に上書きする。
そのため、`.env.test` の `SERVER_PORT` は、`vp preview` の proxy が向ける、ホストから見た backend のポートだけを表す（slot の値は `acquire` が環境変数で渡す）。

## 空きがないとき

- 5 秒ごとに空きを探し直し、`TEST_SLOT_WAIT_SECONDS`（既定は 900 秒）まで待つ。
- 待つ間は 30 秒ごとに、「全ての slot が使用中のため、空くまで待ちます」、残りの秒数、slot ごとの使用中の理由を出す。
  理由は、持ち主の worktree のパスか、別のプロセスが使っているポートの番号である。
- 上限を超えると、slot ごとの理由を出して失敗する。
- 待つ間はロックを持たない。

## 片付け

- 各 Task の後片付けは、自分の project だけを `down --volumes --remove-orphans` で消し、slot を返す。
- `task test` は、`be-verify-migrations` と `be-test` の間で PostgreSQL のコンテナを消さずに止め、最後の `down` まで slot を持つ。
- `E2E_KEEP_ENV=1 task e2e` が失敗して環境を残したときは、片付けるまで slot を持つ。
  残したときは、slot の番号、Compose project、ログの読み方、再実行と片付けのコマンドを出す。
- `task e2e-up` は片付けないので、呼び出し側が片付けるまで slot を持つ。

## 中断した実行が残した slot

中断（Ctrl-C、kill）した実行は、後片付けを走らせずにコンテナを残すことがあり、そのときは slot を持ち続ける。
同じ worktree の次の実行も、その slot を使用中とみなし、別の slot を取る。
worktree を消せば、その slot を次に調べた実行がコンテナを回収する。

worktree を残すときは、slot を持つ project と持ち主を次のコマンドで調べ、自分の project を片付ける。

```bash
docker ps -a --filter name=spring-modulith-test \
  --format '{{.Label "com.docker.compose.project"}} {{.Label "com.docker.compose.project.working_dir"}}' | sort -u
docker compose -p spring-modulith-test-<n> -f docker/compose-test.yml --profile '*' down --volumes --remove-orphans
```

`--profile '*'` は全ての profile を有効にする。
付けないと、profile を持つ Keycloak と backend のコンテナが残り、slot も空かない（Docker Compose v5.6.0 で確かめた）。

中断した `task scan-dast` は、Compose の外で起動した backend のコンテナ（`spring-modulith-dast-<n>-backend`）も残すことがある。
このコンテナは host network で 18081 を使うため、残っている間は次の `task scan-dast` がポートの確認で失敗する。
`docker rm --force spring-modulith-dast-<n>-backend` で消す。

## 既知の制限

- 同じホストの別の clone とはロックを共有しない（ロックは clone ごとの git の共通ディレクトリにある）。
  別の clone から同時に slot を取ると、同じ slot を選ぶ可能性がある。
- 2 つの `task scan-dast` は同時に流せない。
  slot を取れても、2 本目は 4173、18081、18082 の確認で失敗する。
- `task e2e-collect-logs` を単独で実行すると、Compose の既定の project（`spring-modulith-test`）のログを読む。
  `task e2e` の中では、`task e2e` が export した `COMPOSE_PROJECT_NAME` を受け継ぎ、slot の project のログを読む。
- `/verify-demo` の `stack.sh` は、`up` が取った slot を `build/verify/stack/slot` に記録し、`doctor`、`down`、`drive.mjs` がその slot の project とポートを使う。
  journey は `webOrigin` と `idpOrigin` を受け取り、5173 や 8081 を書かない。

## 検査

`task test-slot-test` は、偽の docker で割り当て、待ち、回収、環境変数と env ファイルの値を確かめ、realm の web の origin の数が slot の数と一致することを確かめる。
ケースは `tooling/test-slot/test.sh` が正で、この文書には写さない。
フックと CI で実行する場面は [Lintとテストのリファレンス](lint-and-test.md) にある。
