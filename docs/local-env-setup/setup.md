---
type: Runbook
title: 開発環境構築
description: WSL2 と Docker Desktop を前提にしたローカル開発環境の構築手順。
tags: [runbook, setup, wsl, docker, mise]
---

# 開発環境構築

Kiro Web のサンドボックスで作業する場合は、この文書ではなく [Kiro Web の開発環境](kiro-web.md)を読む。

## 前提条件

- Windows 11（物理メモリ 32GB を想定）
- Docker Desktop
- Visual Studio Code

> **推奨**: WSL2 のメモリ上限を `.wslconfig` で引き上げることをおすすめします。
> デフォルトでは以下の制限が適用されています：
>
> | 設定       | デフォルト値                               |
> | ---------- | ------------------------------------------ |
> | メモリ     | 物理メモリの 50% または 8GB のうち小さい方 |
> | プロセッサ | Windows の論理プロセッサ数と同じ           |
>
> Windows 側で `%USERPROFILE%\.wslconfig` を作成または編集し、以下を記述してください：
>
> ``` ini
> [wsl2]
> memory=24GB   # 物理メモリ 32GB の場合。16GB なら 12GB 程度に調整
> ```
>
> `processors` はデフォルトでホストの全論理プロセッサが使えるため、明示指定は不要です。
>
> ⚠️ このファイルは **UTF-8（BOM なし）** で保存してください。
> UTF-16 や BOM 付きで保存すると設定が無視されます。
> メモ帳で保存する場合は「名前を付けて保存」でエンコードを「UTF-8」に指定してください。
>
> 保存後、WSL を再起動すると反映されます。

## 手順

### 1. WSL インスタンス作成

コマンドプロンプトを実行し、以下を入力：

``` cmd
wsl --install Ubuntu-26.04 --name <任意の名前>
```

`--name` はプロジェクトや用途に合わせて自由に付けてください（例: `dev` など）。

インストール完了後、ユーザー名とパスワードを設定してください。

### 2. Docker Desktop の WSL Integration 設定

1. Docker Desktop を起動
2. **Settings > Resources > WSL Integration** を開く
3. 作成したインスタンス名を有効化
4. **Apply & Restart** をクリック

### 3. WSL インスタンスへの接続

``` cmd
wsl -d <インスタンス名>
```

### 4. 初期セットアップ

``` bash
# 共通ワークスペース作成
sudo mkdir -p /home/projects
sudo chown -R $USER:$USER /home/projects
sudo chmod -R 755 /home/projects

# 基本パッケージインストール
sudo apt update && sudo apt upgrade -y
sudo apt install -y curl git wget unzip jq vim tree gnupg2 software-properties-common extrepo

# mise（Task、betterleaks、iwe、Java、zizmor、Kiro CLI を mise.lock の版で入れる）の apt リポジトリ
sudo extrepo enable mise
sudo apt update && sudo apt install -y mise

# Git基本設定（⚠️ 以下2行は自分の名前・メールアドレスに書き換えてから実行してください）
# git config --global user.name "Your Name"
# git config --global user.email "you@example.com"

git config --global core.autocrlf input
git config --global core.fileMode true
git config --global core.symlinks true
git config --global alias.logs "log --pretty='format:%C(yellow)%h %C(green)%cd %C(cyan)%an %C(reset)%s %C(magenta)%d' --date=format:'%Y-%m-%d %H:%M:%S' --graph"
```

### 5. GitHub CLI のインストールと認証

``` bash
(type -p wget >/dev/null || (sudo apt update && sudo apt install wget -y)) \
  && sudo mkdir -p -m 755 /etc/apt/keyrings \
  && out=$(mktemp) && wget -nv -O$out https://cli.github.com/packages/githubcli-archive-keyring.gpg \
  && cat $out | sudo tee /etc/apt/keyrings/githubcli-archive-keyring.gpg > /dev/null \
  && sudo chmod go+r /etc/apt/keyrings/githubcli-archive-keyring.gpg \
  && sudo mkdir -p -m 755 /etc/apt/sources.list.d \
  && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" | sudo tee /etc/apt/sources.list.d/github-cli.list > /dev/null \
  && sudo apt update \
  && sudo apt install gh -y
```

認証を行います：

``` bash
gh auth login
```

以下のように選択してください：

``` text
? Where do you use GitHub? GitHub.com
? What is your preferred protocol for Git operations on this host? HTTPS
? Authenticate Git with your GitHub credentials? Yes
? How would you like to authenticate GitHub CLI? Login with a web browser
```

ブラウザが開くので、画面の指示に従って認証を完了してください。

認証完了後、Git の認証を GitHub CLI に委任します：

``` bash
gh auth setup-git
```

### 6. リポジトリのクローン

``` bash
cd /home/projects
gh repo clone yokozi-jp/spring-modulith-ai-dlc
cd spring-modulith-ai-dlc
```

### 7. セットアップスクリプトの実行

最初に mise で Task を入れ、その後のセットアップを`task setup`で実行します。

``` bash
cd /home/projects/spring-modulith-ai-dlc
mise trust
mise install --locked
eval "$(mise activate bash --shims)"
task setup
source ~/.bashrc
```

`mise trust` は、この `mise.toml` を読んでよいと mise に伝えます。
`mise.toml` の `http:kiro-cli` が option を持つので、trust するまで mise は設定を読みません。
trust はパスごとに記録され、中身が変わっても取り消されません。
実行する前に `mise.toml` を読んで確かめてください。
worktree を作ったときも、そこで `mise trust` を実行します。

`mise install --locked` は、`mise.toml` の Task、betterleaks、iwe、Java（Amazon Corretto）、zizmor、Kiro CLI を、`mise.lock` と `.mise/locks/` に記録した版と checksum で検証して入れます（[ADR-090](../adr/ADR-090-pin-cli-tools-with-mise-lock.md)）。
`--locked` を付けると、lock に無い版を入れずに止まります。
`task setup` は `04-setup-shell.sh` で、mise の shims を PATH の先頭に通す行を `~/.bashrc` に足します。
`mise.toml` か `mise.lock` が変わったときは、`mise install --locked` だけを実行します。
Kiro CLI の固定を入れる前から使っているチェックアウトでは、pull の後に一度だけ `mise trust` を実行します。
実行しないと、shims と、`task` を呼ぶ Git フックが `not trusted` で止まります。

shims は、今いるディレクトリから上にたどって見つけた `mise.toml` の版を選びます。
そのため `task`、`betterleaks`、`iwe`、`java` は、`mise.toml` のあるチェックアウトの中でだけ動きます。
Gradle は `JAVA_HOME` が無ければ PATH の `java` を使うので、`JAVA_HOME` は設定しません。
VS Code の Java 拡張も、`JAVA_HOME` か PATH の `java` を使います。

#### 公式のインストーラーの Kiro CLI から移るとき

以前は `curl -fsSL https://cli.kiro.dev/install | bash` で `~/.local/bin/kiro-cli` を入れていました。
Ubuntu の `~/.profile` は `~/.bashrc` の後で `~/.local/bin` を PATH の先頭へ足すので、ログインシェルでは公式のインストーラーの版が shims より先に使われます。
`~/.local/bin/kiro-cli` を消します（`kiro-cli-chat` があればそれも消します）。
`type -a kiro-cli` の先頭が mise の shims になることを確かめてください。

Kiro CLI は起動時に自己更新を確かめます。
`kiro-cli settings app.disableAutoupdates true` で止めます。
版は `mise.toml` を書き換えて上げます。

#### apt の Corretto から移るとき

`~/.bashrc` に `export JAVA_HOME=/usr/lib/jvm/java-25-amazon-corretto` がある環境では、その行と `export JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"` の行を消し、`source ~/.bashrc` を実行します。
`JAVA_HOME` が残ると、Gradle は mise の Java でなく apt の Java を使います。
`file.encoding` は Java 18 から既定で UTF-8 なので、設定は要りません。

`mise.toml` に Java がないブランチや worktree（この変更より前に切ったもの）では、shims の `java` が版を選べず、`./gradlew` が失敗します。
そうしたブランチを使い終えるまでは、apt の `java-25-amazon-corretto-jdk` を残します。
使い終えたら、`sudo apt remove java-25-amazon-corretto-jdk` で消せます。

### 8. Git hooks のセットアップ

``` bash
cd /home/projects/spring-modulith-ai-dlc
npm ci
```

これにより Lefthook が有効化されます。

### 9. VSCode から WSL への接続

1. Windows 側で VSCode を起動
2. `F1` キーを押してコマンドパレットを開く
3. `WSL: Connect to WSL using Distro...` を選択
4. 手順 1 で作成したインスタンス名を選択

VSCode が WSL モードで再起動し、左下のステータスバーに `WSL: <インスタンス名>` と表示されれば接続完了です。

### 10. iwe（ナレッジ管理）

[iwe](https://github.com/iwe-org/iwe) は markdown ベースのナレッジ管理ツールです（CLI、LSP サーバー、MCP サーバー）。
このプロジェクトでは `docs/` 配下のドキュメント（ADR など）を対象に、リンク補完、バックリンク、定義ジャンプや AI エージェント連携に使い、`task okf-check` で OKF バンドルの適合を検証します。

iwe は手順 7 の `mise install --locked` で導入済みです。
バージョンは `mise.toml` に固定し、チームと CI で揃えます。

`iwe`（CLI）、`iwes`（LSP サーバー）、`iwec`（MCP サーバー）の 3 コマンドが入ります。

`docs/` は OKF v0.2 バンドルとして初期化済みで、その設定 `docs/.iwe/` はリポジトリにコミットされています。
クローンした時点で使える状態なので、`iwe init` を実行する必要はありません。

> **なぜ `docs/` を対象にするのか**: iwe は人が育てる markdown のナレッジグラフを扱うツールです。
> `.kiro/` 配下の markdown は Kiro の設定や生成物なので対象にせず、リポジトリルートではなく `docs/` に絞ります。

エディタ連携（LSP）と AI エージェント連携（MCP）は設定済みで、追加作業は要りません。

- **エディタ連携（LSP）**: 手順 11 の推奨拡張に含まれる `iwe.iwe` 拡張が、mise の shims から `iwes` を LSP サーバーとして利用します。
  定義ジャンプ、バックリンク、リンク補完などが markdown で使えます（詳細は [VS Code 向けドキュメント](https://iwe.md/docs/editors/vscode/)）。
- **AI エージェント連携（MCP）**: MCP サーバー `iwec` の設定は `.kiro/settings/mcp.json` にコミット済みです（`docs/local-env-setup/iwe-mcp.sh` が作業ディレクトリを `docs/` に固定して起動）。
  Kiro を再起動すれば利用できます。

### 11. VSCode 拡張機能のインストール

`.vscode/extensions.json` に記載されている推奨拡張機能をインストールしてください。

### 12. 動作確認

``` bash
cd /home/projects/spring-modulith-ai-dlc
docker info
java -version
node -v
gh auth status
task --version
kiro-cli --version
betterleaks version
iwe --version
```

エラーなく各ツールの情報が表示されれば環境構築完了です。
