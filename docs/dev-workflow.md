# 開発ワークフロー

`task`の公開タスクは多いが、日常的に打つのは少数の**入口タスク**で足りる。
このドキュメントは「いつ、どのコマンドを、どの順で使うか」をシナリオ別に示す地図である。
公開タスクの一覧と説明は`task`（引数なし）、`task help`、または`task --list`で確認できる。

## 入口タスク（まずこれだけ覚える）

- **`task dev`**：依存サービスを起動してバックエンドを起動する。日々の開発の入口。
- **`task check`**：素早いローカル確認（バックエンドの静的解析）。こまめに回す。
- **`task verify`**：push 前の総合ゲート（静的解析、OpenAPI 契約検査と、使い捨てDBでのマイグレーション検証とテスト）。CI と同じ内容。
- **`task e2e`**：E2E（未整備。`docs/e2e-testing-strategy.md` に沿って構築予定）。

これ以外の細かいタスクは、上記やCI、Gitフックから呼ばれる**部品**である。
`task help`は公開タスクの名前と説明を一覧表示する。

## シナリオ別の手順

### 日々の開発ループ

```bash
task dev                   # 依存起動＋バックエンド起動
# 別のターミナルで
cd frontend && vp dev      # SPAを起動し、APIとOIDC関連パスを同一オリジンでproxy
# コードを変更する
task check                 # 静的解析で素早く確認（こまめに）
```

ブラウザは <http://localhost:5173> を開き、Vite proxy 経由でバックエンドを利用する。
バックエンドは <http://localhost:18080>、Keycloak は <http://localhost:8080>、
Grafana は <http://localhost:3000> で待ち受ける。

### マイグレーションを追加するとき

```bash
# 1. changeset を追加する（backend/src/main/resources/db/changelog/changesets/）
task be-migrate            # 現在のスキーマタグまで適用（タグ確定後）
# または開発中は
task be-migrate-dev        # 作りかけを含む全 changeset を適用
task be-refresh-jooq       # 最新スキーマから jOOQ 生成コードを更新
task be-verify-migrations  # 使い捨てDBで update→rollback→再update とタグを検証
```

changeset と jOOQ 生成コードは同じ変更として Git 管理する。
詳細は `docs/database-migrations.md` を参照。

### push する前

```bash
task verify                # be-lint + 使い捨てDBでのマイグレーション検証とテスト（CI と同じ）
```

`task verify` は `.env.test` の隔離スタック（PostgreSQL 5433 / Redis 6380）を
起動し、マイグレーション検証とテストを実行して後片付けまで行う。
開発用スタック（5432 / 6379）とポートが分かれているため、`task dev` で
バックエンドを起動したまま並行実行できる。

### ミューテーションテストを実行するとき

```bash
task mutation-test
```

`task mutation-test`は使い捨てのテスト用依存を起動し、マイグレーション検証後にPITを実行して片付ける。
PITは通常テストより実行コストが高いため、`task verify`とpull requestの必須CIには含めない。
結果は、変異対象が存在するときに`backend/build/reports/pitest/`へ出力される。

push 時には `task adr-check` が pre-push で走り、判断が絡む変更（依存・
セキュリティ・DB・インフラ・ワークフロー、およびフロントエンド全体）に
`docs/adr/` の更新が伴わないとき警告する。
既定は非ブロッキングのナッジで、該当しなければ `ADR_ACK=1 git push` で抑制できる。
フロントエンドは構築初期のため全体を対象にしている。安定したら
`frontend/package.json` や設定ファイルなど判断が出やすい箇所へ絞ってよい。

### Docker Compose とサービスの操作

依存サービス（PostgreSQL / Keycloak / Redis / Grafana）は Docker Compose で起動する。
通常はこれらだけを起動し、バックエンドは `task be-run`（`task dev` の一部）でホスト上に立てる。
`.env` はルートの `.env.example` をコピーして用意し、パスワードを変更しておく。

```bash
task compose-ps            # サービスの状態を確認
task compose-up-backend    # backend profile も有効にしてコンテナで起動
```

Keycloak の OIDC discovery、issuer、PKCE S256 対応を確認する。ログの追跡も同様。

```bash
task oidc-check
task keycloak-logs
```

`realm.json` を変更しても、既存 realm は起動時インポートで上書きされない。
Keycloak のローカルデータだけを削除して realm を再投入するには `task keycloak-reimport` を使う。
これは PostgreSQL、Redis、Grafana のデータを保持する。

```bash
task keycloak-reimport
```

停止と初期化は次のとおり。

```bash
task compose-down          # 停止（named volume は保持）
task compose-reset CONFIRM_RESET=yes   # 全サービスの volume ごと削除して初期化（データは失われる）
```

`compose-reset` は全サービスのデータを削除するため、確認変数 `CONFIRM_RESET=yes` を指定しないと実行されない。

## ローカル・CI・フックの一致

同じTaskfileのタスクを、ローカル、CI、Gitフック（Lefthook）が共用している。
これにより「ローカルでは通ったが CI で落ちる」乖離を防ぐ。

- pre-commit：betterleaks、hadolint、compose config、markdownlint（変更種別に応じて）。
- pre-push：betterleaks（全履歴）、backend 変更時は be-lint / be-test、
  判断が絡む変更に ADR が伴うかの確認（`task adr-check`。既定は非ブロッキングのナッジ）。
- CI：`backend-ci.yml` が `task be-verify-migrations` と `task be-test` を実行。

したがって、ローカルで `task verify` が通れば CI もほぼ通る。

## 入口タスクと部品の関係

- `task dev` → `compose-up` ＋ `be-run`
- `task check` → `be-lint`
- `task verify` → `be-lint` ＋ `test`（`test` は隔離スタック起動＋
  `be-verify-migrations` ＋ `be-test` ＋ 後片付け）

部品タスク（`be-test`、`be-mutation-test`、`test-deps-up` など）は通常直接打たず、
入口タスクや CI から呼ばれる。
