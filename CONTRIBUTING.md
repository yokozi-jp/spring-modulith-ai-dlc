# コントリビューションガイド

## 開発コマンド

このリポジトリは Make ではなく [Task](https://taskfile.dev/) を共通入口に使う。

利用できるタスクは次のコマンドで確認する。

```bash
task --list
```

初回セットアップとローカル起動は次の順で行う。

```bash
task setup
cp .env.example .env
task compose-up
task be-migrate
task dev
```

日常的な検証には次のタスクを使う。

- **`task check`**：バックエンドの静的解析を実行する。
- **`task verify`**：静的解析、DB マイグレーション検証、テスト、OpenAPI 検査を実行する。
- **`task be-format`**：バックエンドを整形する。
- **`task lint-md`**：Markdown を検査する。
- **`task lint-actions`**：GitHub Actions を actionlint で検査する。
- **`task lint-actions-security`**：GitHub Actions を zizmor で検査する。
- **`task release-check`**：release-please の設定と版ファイルの一致を検査する。

frontend を変更した場合は `frontend/` で Vite+ の検査も実行する。

```bash
cd frontend
vp install
vp check
vp test
```

詳しい起動手順は [`README.md`](README.md)、検証内容は [`docs/lint-and-test.md`](docs/lint-and-test.md) を参照する。

## ブランチ運用

長期ブランチは `main` だけとし、短命な作業ブランチから Pull Request を作る。

作業開始前に `main` を更新し、変更種別が分かる名前を付ける。

```text
feat/<short-description>
fix/<short-description>
docs/<short-description>
chore/<short-description>
```

`main` へ直接 push せず、force push とブランチ履歴の不要な書き換えを避ける。

Pull Request は squash merge し、そのタイトルを `main` に残る Conventional Commit メッセージとして扱う。

GitHub 側の設定値と必須チェックは [`docs/branch-protection.md`](docs/branch-protection.md) に定義する。

## コミット規約

コミットと Pull Request のタイトルは [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) に従う。

使用できる type は `build`、`chore`、`ci`、`docs`、`feat`、`fix`、`perf`、`refactor`、`revert`、`style`、`test` である。

scope は任意だが、`backend`、`frontend`、`infra`、`docs`、`release` のように変更境界を短く示す。

```text
feat(backend): publish domain events after commit
fix(frontend): preserve locale during sign-in
docs: document local database reset
```

破壊的変更は type または scope の後へ `!` を付けるか、footer に `BREAKING CHANGE:` を記載する。

```text
feat(api)!: replace legacy error response
```

ローカルの `commit-msg` hook が commitlint を実行する。

Pull Request のタイトルも同じ形式にし、squash merge 時のメッセージを手作業で別形式へ変えない。

## Pull Request

Pull Request テンプレートに変更概要、テスト結果、リリースと運用への影響、ブロッカーを記載する。

レビュー依頼前に、変更範囲に応じた最小の検査を実行する。

通常は `task verify` を実行し、frontend、文書、workflow を変更した場合は対応する検査を追加する。

重要な設計判断を含む変更では、実装と同じ Pull Request に Proposed 状態の ADR を追加する。

レビュー会話を解決し、必須チェックと CODEOWNERS のレビューを通してから merge する。

## リリース

release-please が `main` の Conventional Commits から Release Pull Request を更新する。

通常の変更で `CHANGELOG.md`、`.release-please-manifest.json`、`backend/build.gradle` の版番号を手作業で更新しない。

Release Pull Request で次版と CHANGELOG を確認し、その Pull Request を merge すると `v<version>` タグと GitHub Release が作られる。

release-please は成果物の公開と本番デプロイを行わない。

## セキュリティ

脆弱性の詳細を公開 Issue や Pull Request に書かない。

報告方法は [`SECURITY.md`](SECURITY.md) に従う。
