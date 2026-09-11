# 開発ワークフロー

`make` のターゲットは多いが、日常的に打つのは少数の**入口タスク**で足りる。
このドキュメントは「いつ、どのコマンドを、どの順で使うか」をシナリオ別に示す地図である。
ターゲットの一覧と説明は `make`（引数なし）または `make help` で確認できる。

## 入口タスク（まずこれだけ覚える）

- **`make dev`**：依存サービスを起動してバックエンドを起動する。日々の開発の入口。
- **`make check`**：素早いローカル確認（バックエンドの静的解析）。こまめに回す。
- **`make verify`**：push 前の総合ゲート（静的解析と、使い捨てDBでのマイグレーション検証とテスト）。CI と同じ内容。
- **`make e2e`**：E2E（未整備。`docs/e2e-testing-strategy.md` に沿って構築予定）。

これ以外の細かいターゲットは、上記や CI・Git フックから呼ばれる**部品**である。
`make help` はカテゴリ（Workflow / Setup / Backend / Database / Compose /
Security / Lint）ごとに一覧を表示する。

## シナリオ別の手順

### 初回セットアップ

```bash
make setup                 # 言語・ツールの導入（実行後に source ~/.bashrc）
cp .env.example .env       # 環境変数を用意し、パスワードを変更する
make compose-up            # PostgreSQL / Keycloak / Redis / Grafana を起動
make be-migrate            # 初回はマイグレーションを明示実行する
```

### 日々の開発ループ

```bash
make dev                   # 依存起動＋バックエンド起動
# コードを変更する
make check                 # 静的解析で素早く確認（こまめに）
```

バックエンドは <http://localhost:18080>、Keycloak は <http://localhost:8080>、
Grafana は <http://localhost:3000>。

### マイグレーションを追加するとき

```bash
# 1. changeset を追加する（backend/src/main/resources/db/changelog/changesets/）
make be-migrate            # 現在のスキーマタグまで適用（タグ確定後）
# または開発中は
make be-migrate-dev        # 作りかけを含む全 changeset を適用
make be-refresh-jooq       # 最新スキーマから jOOQ 生成コードを更新
make be-verify-migrations  # 使い捨てDBで update→rollback→再update とタグを検証
```

changeset と jOOQ 生成コードは同じ変更として Git 管理する。
詳細は `docs/database-migrations.md` を参照。

### push する前

```bash
make verify                # be-lint + 使い捨てDBでのマイグレーション検証とテスト（CI と同じ）
```

`make verify` は `.env.test` の隔離スタック（PostgreSQL 5433 / Redis 6380）を
起動し、マイグレーション検証とテストを実行して後片付けまで行う。
開発用スタック（5432 / 6379）とポートが分かれているため、`make dev` で
バックエンドを起動したまま並行実行できる。

push 時には `make adr-check` が pre-push で走り、判断が絡む変更（依存・
セキュリティ・DB・インフラ・ワークフロー、およびフロントエンド全体）に
`docs/adr/` の更新が伴わないとき警告する。
既定は非ブロッキングのナッジで、該当しなければ `ADR_ACK=1 git push` で抑制できる。
フロントエンドは構築初期のため全体を対象にしている。安定したら
`frontend/package.json` や設定ファイルなど判断が出やすい箇所へ絞ってよい。

### E2E を回すとき（整備後）

```bash
make e2e                   # フルスタック起動→シード→playwright test（予定）
```

現時点では未整備。方針は `docs/e2e-testing-strategy.md` を参照。

### Docker Compose とサービスの操作

依存サービス（PostgreSQL / Keycloak / Redis / Grafana）は Docker Compose で起動する。
通常はこれらだけを起動し、バックエンドは `make be-run`（`make dev` の一部）でホスト上に立てる。
`.env` はルートの `.env.example` をコピーして用意し、パスワードを変更しておく。

```bash
make compose-ps            # サービスの状態を確認
make compose-up-backend    # backend profile も有効にしてコンテナで起動
```

Keycloak の OIDC discovery、issuer、PKCE S256 対応を確認する。ログの追跡も同様。

```bash
make oidc-check
make keycloak-logs
```

`realm.json` を変更しても、既存 realm は起動時インポートで上書きされない。
Keycloak のローカルデータだけを削除して realm を再投入するには `make keycloak-reimport` を使う。
これは PostgreSQL、Redis、Grafana のデータを保持する。

```bash
make keycloak-reimport
```

停止と初期化は次のとおり。

```bash
make compose-down          # 停止（named volume は保持）
make compose-reset CONFIRM_RESET=yes   # 全サービスの volume ごと削除して初期化（データは失われる）
```

`compose-reset` は全サービスのデータを削除するため、確認変数 `CONFIRM_RESET=yes` を指定しないと実行されない。

## ローカル・CI・フックの一致

同じ `make` ターゲットを、ローカル・CI・Git フック（Lefthook）が共用している。
これにより「ローカルでは通ったが CI で落ちる」乖離を防ぐ。

- pre-commit：betterleaks、hadolint、compose config、markdownlint（変更種別に応じて）。
- pre-push：betterleaks（全履歴）、backend 変更時は be-lint / be-test、
  判断が絡む変更に ADR が伴うかの確認（`make adr-check`。既定は非ブロッキングのナッジ）。
- CI：`backend-ci.yml` が `make be-verify-migrations` と `make be-test` を実行。

したがって、ローカルで `make verify` が通れば CI もほぼ通る。

## 入口タスクと部品の関係

- `make dev` → `compose-up` ＋ `be-run`
- `make check` → `be-lint`
- `make verify` → `be-lint` ＋ `test`（`test` は隔離スタック起動＋
  `be-verify-migrations` ＋ `be-test` ＋ 後片付け）

部品ターゲット（`be-test`、`test-deps-up` など）は通常直接打たず、
入口タスクや CI から呼ばれる。
