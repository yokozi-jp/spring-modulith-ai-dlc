# ADR-010: プロジェクトのタスクランナーにTaskを採用する

## Status

Proposed

## Date

2026-09-13

## Context

開発コマンド、DBマイグレーション、テスト、Lint、セキュリティ検査は、ルートの`Makefile`を共通の入口としている。
GNU Makeはファイル生成の依存関係を扱うビルドツールであり、このプロジェクトではすべてのターゲットを`.PHONY`として宣言し、タスクランナーとして利用している。
そのため、厳格なシェル設定、暗黙ルールの無効化、セルフドキュメント用の`awk`、シェル変数の二重エスケープなど、タスクの処理とは直接関係しないMake固有の設定が必要になっている。

開発環境はWSL上のLinuxを基準とするが、タスク定義にはYAMLで説明、作業ディレクトリ、環境ファイル、内部タスク、前提条件を明示できる方が保守しやすい。
ローカル、Gitフック、CIが同じタスクを呼ぶ構造と、既存のタスク名および安全策は維持する必要がある。

## Decision

プロジェクトのタスクランナーとして[Task](https://taskfile.dev/)を採用し、ルートの`Makefile`を`Taskfile.yml`へ置き換える。

- Taskのバージョンは`3.53.1`へ固定し、ローカル導入スクリプトとCIで同じバージョンを使う。
- 既存の公開タスク名を維持し、呼び出しを`make <task>`から`task <task>`へ変更する。
- タスク定義はルートの単一`Taskfile.yml`へ置き、分割が必要になるまでincludeを導入しない。
- 順序が必要な処理は`deps`ではなく`cmds`内のタスク呼び出しで直列に実行する。
- ローカル用`.env`とテスト用`.env.test`は必要なタスクだけが読み、本番DB操作はローカルの環境ファイルを読まない。
- DB切り戻しとComposeのデータ削除では、既存の明示確認変数を維持する。
- テスト用Composeは、処理の成否にかかわらず停止し、元の失敗または後片付けの失敗を終了コードへ反映する。
- Taskの導入だけでは既存コマンドがWindowsネイティブ対応になるとは見なさず、実行環境は引き続きWSL上のLinuxとする。

## Consequences

### Positive

- タスクの説明、作業ディレクトリ、内部公開範囲、前提条件をTaskfileの構造として記述できる。
- Make固有の暗黙ルール、`.PHONY`、タブ字下げ、`$`の二重エスケープが不要になる。
- `task --list`と`task --summary`で、追加の`awk`を保守せずタスクを確認できる。
- ローカル、Gitフック、CIが同じタスク定義を共用する構造を維持できる。

### Negative

- 開発端末とCIへTaskバイナリを追加で導入する必要がある。
- Taskが未導入の環境では`task setup`を実行できないため、独立したブートストラップ手順が必要になる。
- Taskの`deps`は並列実行されるため、Makeの前提ターゲットを機械的に置換すると実行順序が変わる。
- TaskのGoテンプレートと、Dockerなど別のGoテンプレートを同じコマンドで使う場合は衝突を避ける必要がある。

### Neutral

- Gradle、Docker Compose、betterleaks、各Lintツールの選定と実行内容は変更しない。
- 公開タスク名、DB操作の影響範囲、ローカルとテストのポート分離は変更しない。

## Alternatives Considered

### Alternative 1: GNU Makeを継続する

- 説明：現在の`Makefile`と呼び出し方法を維持する。
- Pros：新しい実行ファイルの導入が不要で、既存利用者の操作が変わらない。
- Cons：タスクランナー用途のためにMake固有の安全設定とセルフドキュメント処理を保守し続ける必要がある。

### Alternative 2: Gradleとnpm scriptsへ分散する

- 説明：バックエンド処理をGradleへ、フロントエンド処理をnpm scriptsへ置き、Composeと横断処理はシェルスクリプトへ分ける。
- Pros：アプリケーションですでに使っている実行基盤へ寄せられる。
- Cons：リポジトリ全体の入口が複数に分かれ、ローカル、Gitフック、CIで同じ処理を呼ぶ構造が見えにくくなる。

### Alternative 3: Justを採用する

- 説明：コマンドランナーであるJustへ移行する。
- Pros：Makeよりタスク実行に適した簡潔な構文を使える。
- Cons：YAMLでタスクを定義できること、タスク単位のdotenv、内部タスク、公式のGitHub Actionという今回の要件ではTaskの方が適合する。

## References

- [Task Installation](https://taskfile.dev/docs/installation)
- [Task Guide](https://taskfile.dev/docs/guide)
- [Taskfile Schema](https://taskfile.dev/docs/reference/schema)
- [`docs/dev-workflow.md`](../dev-workflow.md)
- [`docs/lint-and-test.md`](../lint-and-test.md)
