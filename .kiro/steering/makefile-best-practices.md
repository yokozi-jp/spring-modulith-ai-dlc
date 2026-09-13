---
inclusion: fileMatch
fileMatchPattern: ["**/Makefile", "**/makefile", "**/*.mk", "**/*.make", "gradle/*.gradle"]
name: makefile-best-practices
description: ルートの Makefile を新規作成・編集するときのベストプラクティスとアンチパターン。厳格な前文（SHELL・strict mode・.DELETE_ON_ERROR・MAKEFLAGS）、.PHONY、既定ゴールとセルフドキュメント help、変数の上書きと展開、冪等性と後片付け、破壊的ターゲットの確認ゲート、外部ツールのバージョン固定、命名とカテゴリ、CI とフックとの一致、修正時のドキュメント同期を定める。GNU Make のタスクランナー用途を前提とする。Makefile を書く・直すときに使用する。
---

# Makefile の書き方

Makefile を作成・編集するときは、以下に従う。

このプロジェクトの Makefile は、ビルド成果物を組み立てるためではなく、開発コマンドの入口（タスクランナー）として使う。
実行環境は GNU Make 4.x とする。
基準となる実ファイルは #[[file:Makefile]] を参照する。

タスクランナーとして使う場合でも、Make の既定はファイルビルド向けに寄っているため、素の Make に任せると意図しない挙動を招く。
以下の前文と規約は、その既定を安全側へ倒すためのものである。

## 厳格な前文

Makefile の先頭に、次の前文を置く。

```makefile
SHELL := bash
.ONESHELL:
.SHELLFLAGS := -eu -o pipefail -c
.DELETE_ON_ERROR:
MAKEFLAGS += --warn-undefined-variables
MAKEFLAGS += --no-builtin-rules
```

それぞれの意図は次のとおり。

- **`SHELL := bash`**：レシピを実行するシェルを固定する。既定の `/bin/sh` は環境ごとに dash や別実装を指すため、`/dev/tcp` や `trap` などを使うなら bash を明示する。
- **`.ONESHELL:`**：一つのレシピ全体を単一のシェルで実行する。行ごとに新しいシェルが立つ既定では `cd` や変数代入が次の行へ引き継がれないが、これを有効にすると普通のシェルスクリプトのまま書ける。行末の `\` 連結や `;` が不要になり、連結漏れのバグも減る。
- **`.SHELLFLAGS := -eu -o pipefail -c`**：レシピのシェルを strict mode で走らせる。未定義変数の参照とパイプ途中の失敗を検出する。`.ONESHELL:` と併せると、レシピの途中で失敗した時点で止まり、環境ファイルの読み込み失敗のまま後続のコマンドが走る事故を防ぐ。
- **`.DELETE_ON_ERROR:`**：レシピが失敗したとき、生成途中のターゲットファイルを削除する。壊れた中間ファイルを次回に「生成済み」と誤認させない。
- **`--warn-undefined-variables`**：未定義の Make 変数を参照したときに警告する。変数名の打ち間違いに早く気付ける。コマンドラインから渡す変数（`DB_ROLLBACK_TAG` など）は、先頭で `?=` で空に定義しておけば警告を避けられる。
- **`--no-builtin-rules`**：暗黙のビルトインルールを無効にする。拡張子から勝手にビルドを試みる Make の推論を止める。

### .ONESHELL を使うときの二つの注意

`.ONESHELL:` は Make の既定（行ごとに独立したシェル）を変えるため、複数行レシピの書き方に二つの制約が生まれる。
このプロジェクトは `.ONESHELL:` を使うので、複数行レシピは次を守る。

- **ディレクトリ移動はサブシェルに閉じ込める**。`.ONESHELL:` では `cd` が後続の行へ残る。ある行で `cd backend` し、次の行で元の場所を前提に `docker run -v "$$PWD/backend"` するようなレシピは壊れる。`cd` はサブシェル `( cd backend && ... )` に閉じ込め、次の行の `$$PWD` を元のディレクトリに保つ。
- **2 行目以降の行頭に `@`・`-`・`+` を置かない**。`.ONESHELL:` では 1 行目の接頭辞だけがレシピ全体に適用され、2 行目以降の `@` はシェルへ literal に渡って `@echo: command not found` になる。コマンドの表示を抑止したいなら 1 行目に `@` を置けばレシピ全体に効く。

```makefile
# 悪い例：cd が漏れ、2 行目の @ がコマンドとして解釈される
scan:
	cd backend && ./gradlew build
	@docker run -v "$$PWD/backend":/src ...

# 良い例：cd はサブシェルに閉じ込め、@ は 1 行目だけ
scan:
	@( cd backend && ./gradlew build )
	docker run -v "$$PWD/backend":/src ...
```

タブとスペースの混在を避けたい場合は、レシピ接頭辞をタブ以外へ変える方法もある。
ただしプロジェクト全体のレシピ書式に影響するため、導入は任意とする。

```makefile
ifeq ($(origin .RECIPEPREFIX), undefined)
  $(error This Make does not support .RECIPEPREFIX. Please use GNU Make 4.0 or later)
endif
.RECIPEPREFIX = >
```

## .PHONY

ファイルを生成しないターゲット（`dev`、`check`、`test` など）は、すべて `.PHONY` に宣言する。

宣言しないと、Make はターゲット名と同名のファイルの有無で実行要否を判断する。
たとえば `test` という名前のファイルが偶然存在すると、`make test` が「生成済み」と見なされて何も実行されない。

宣言はターゲットの直前に個別に書いてもよいし、先頭に一括で書いてもよい。
どちらの流儀でも、新しいターゲットを追加したら `.PHONY` への追加も忘れない。

```makefile
.PHONY: check
check: be-lint
```

## 既定ゴールとセルフドキュメント help

引数なしの `make` では、破壊的だったり時間のかかったりするターゲットを実行させない。
既定ゴールを `help` にして、ターゲットの一覧を表示する。

```makefile
.DEFAULT_GOAL := help
```

ターゲットの説明は、ターゲットの直前で `##` に続けて書く（`## 説明` の行）。
カテゴリの見出しは `##@` に続けて書く（`##@ カテゴリ名` の行）。
`help` は `$(MAKEFILE_LIST)` を awk で読み、これらの注釈から一覧を生成する。
説明をコード（各ターゲット）の隣に置くことで、ターゲットの追加時に説明が置き去りにならない。

```makefile
##@ Workflow（日々の入口）
## 素早いローカル確認（バックエンドの静的解析）。こまめに回す。
check: be-lint
```

## 変数の上書きと展開

- 環境やCIから差し替えたい値は `?=` で定義し、外部からの上書きを許す（`COMPOSE_ENV_FILE ?= .env` など）。
- 即時に確定させたい値は `:=`（単純展開）で定義し、参照のたびに再評価される `=`（再帰展開）の意図しない遅延評価を避ける。
- 外部コマンドの結果は `$(shell ...)` で取り込む。
- シェル変数はレシピ内で `$$VAR` と書く。`$` は Make の変数展開に使われるため、シェルへ渡すには `$$` にエスケープする。

## 冪等性と後片付け

- 依存サービスの起動からテスト、後片付けまでを束ねるワンショットのターゲットは、途中で失敗しても資源を残さないよう `trap` で後片付けする。
- 失敗時にも後片付けを走らせ、かつ元の終了コードを保つ。

```makefile
test:
	@set -eu; \
	cleanup() { \
		status=$$?; \
		trap - EXIT; \
		$(TEST_COMPOSE) down --volumes --remove-orphans || true; \
		exit "$$status"; \
	}; \
	trap cleanup EXIT; \
	$(TEST_COMPOSE) up -d --wait; \
	$(MAKE) be-test
```

前文で `.ONESHELL:` と `.SHELLFLAGS := -eu -o pipefail -c` を効かせているため、レシピは単一シェルの strict mode で走る。
`trap` で登録した後片付けは、レシピの途中で失敗しても EXIT 時に走る。
ワンショットのターゲットは「起動 → 実行 → 必ず後片付け」を保証する。

## 破壊的ターゲットの確認ゲート

- データやスキーマを失わせるターゲット（DB切り戻し、volume 削除など）は、明示の確認変数を要求してから実行する。
- 確認変数が無ければ、何をすべきかを案内して非ゼロで終了する。

```makefile
be-rollback: _require-migration-env
	@set -eu; \
	if [ "$(CONFIRM_ROLLBACK)" != "yes" ]; then \
		echo "rollback preview とバックアップを確認し、CONFIRM_ROLLBACK=yes を指定してください。" >&2; \
		exit 1; \
	fi; \
	...
```

- 本番の資格情報は、ローカルの `.env` を読ませず、CI/CD が注入した専用の環境変数だけを使う。前提チェックのターゲット（`_require-*`）で未設定を早期に弾く。

## 外部ツールのバージョン固定

- Docker で動かす lint やスキャンは、イメージをタグと digest（`@sha256:...`）で固定する。タグが差し替わってもローカルとCIで同じ結果になる。
- ローカルとCIとGitフックは、同じ `make` ターゲットを共用する。「ローカルでは通ったがCIで落ちる」乖離を防ぐ。

```makefile
lint-semgrep:
	docker run --rm ... \
		semgrep/semgrep:1.175.0@sha256:b94b53d02fd4a... \
		semgrep scan ...
```

## 命名とカテゴリ

- ターゲット名は用途が分かる具体的なものにする。関連するターゲットは接頭辞でそろえる（`be-*` はバックエンド、`compose-*` は Compose、`scan-*` はスキャン、`lint-*` は lint）。
- `##@` のカテゴリ見出しで、日々の入口、Setup、Backend、Database、Compose、Security、Lint のように束ねる。
- 直接叩かない内部の部品ターゲットは、前提チェックの `_require-migration-env` のように接頭辞で区別する。

## CI とフックとの一致

- CIワークフローとGitフック（Lefthook）は、シェルスクリプトを重複して書かず `make` ターゲットを呼ぶ。ロジックの単一の置き場所を Makefile に保つ。
- 入口タスク（`make verify` など）が呼ぶ部品ターゲット（`be-lint`、`test` など）は、CIからも同じものを呼ぶ。

## 修正時のドキュメント同期

Makefile を修正したら、ターゲットや使い方に言及している次のドキュメントを確認し、必要に応じて同時に更新する。
コマンドの追加、削除、改名、挙動変更が、ドキュメントとずれたまま残らないようにする。

- **README**：#[[file:README.md]] の「開発コマンド」「Lint・テスト」節（入口タスクの一覧と `make verify` の説明）
- **開発ワークフロー**：#[[file:docs/dev-workflow.md]]（入口タスク、シナリオ別の手順、Docker Compose の操作、入口タスクと部品の関係）
- **Lint・テストのリファレンス**：#[[file:docs/lint-and-test.md]]（各 `make` ターゲットの一覧表と、Gitフック・CIでの自動実行の対応）

たとえばターゲットを改名したら、README と dev-workflow.md のコマンド例、lint-and-test.md の表を同じ名前へ直す。
ターゲットを追加したら、`##` の注釈（`make help` に載る）に加えて、リファレンス表への追記も検討する。

## アンチパターンまとめ

- 厳格な前文を置かず、失敗したレシピの後続が走り続ける。
- `.ONESHELL:` を有効にしたのに、`cd` をサブシェルに閉じ込めず、後続の行へ作業ディレクトリが漏れる。
- `.ONESHELL:` を有効にしたのに、2 行目以降の行頭に `@` を置き、`@echo` などがコマンドとして解釈されて失敗する。
- ファイルを生成しないターゲットを `.PHONY` に宣言せず、同名ファイルの有無で実行がスキップされる。
- 既定ゴールを設定せず、引数なしの `make` が先頭の（重い・破壊的な）ターゲットを実行する。
- ターゲットの説明を別の場所に集約し、追加時に説明が置き去りになる。
- シェル変数を `$VAR` と書き、Make に変数として展開されてしまう。
- 破壊的ターゲットを確認ゲートなしで実行できるようにする。
- 本番マイグレーションでローカルの `.env` を読んでしまう。
- Docker イメージを可変タグ（`latest` など）だけで参照し、実行時期で結果が変わる。
- CIやフックにシェルスクリプトを重複して書き、Makefile と二重管理になる。
- Makefile を直したのに README、dev-workflow.md、lint-and-test.md を更新せず、記述がずれる。

## 出典

- GNU Make Manual（<https://www.gnu.org/software/make/manual/>）
- Your Makefiles are wrong（<https://tech.davis-hansson.com/p/make/>）
- Most Makefiles Should .DELETE_ON_ERROR（<https://innolitics.com/articles/make-delete-on-error/>）
- Self-Documented Makefile（<https://marmelab.com/blog/2016/02/29/auto-documented-makefile>）
