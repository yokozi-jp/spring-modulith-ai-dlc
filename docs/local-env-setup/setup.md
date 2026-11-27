---
type: Runbook
title: 開発環境構築
description: Windows 11 の WSL2 (Ubuntu 26.04) と Docker Desktop に、clone してから task setup、依存の導入、動作確認が通るまでのローカル開発環境を作る手順。新しく開発に参加するとき、マシンを作り直したときに読む。
tags: [runbook, setup, wsl, docker, mise]
---

# 開発環境構築

Windows 側で WSL2 のメモリ上限を設定して Ubuntu を作り、Ubuntu 側で2つのブロックを貼り付けて実行します。
ブロック1が apt のパッケージ、GitHub CLI、mise、Git の設定を入れます。
ブロック2が GitHub の認証、clone、道具と依存の導入、動作確認までを行います。

Kiro Web のサンドボックスで作業する場合は、この文書ではなく [Kiro Web の開発環境](kiro-web.md)を読みます。

## 前提

- Windows 11（物理メモリ 32GB を想定）
- Docker Desktop
- Visual Studio Code とその WSL 拡張機能

hook のスクリプトは POSIX sh で書いているので、Windows では WSL2 上で開発します。

## 1. WSL2 のメモリ上限と Ubuntu（Windows 側）

WSL2 の VM の既定のメモリ上限は、Windows の物理メモリより小さい値です。
Docker Desktop のコンテナも同じ VM で動き、この上限を分け合うので、`%USERPROFILE%\.wslconfig` で引き上げます。
`processors` は既定で全部使えるので、指定しません。

Docker Desktop を終了してから、PowerShell で次を実行します。

``` powershell
$memory = '24GB'   # 物理メモリ 32GB の場合。16GB なら 12GB 程度
$distro = 'dev'    # WSL のインスタンス名。用途に合わせて変えてよい
$config = "$env:USERPROFILE\.wslconfig"
if (Test-Path $config) {
  Write-Warning "$config は既にあります。[wsl2] の memory を手で $memory にしてください。"
} else {
  [IO.File]::WriteAllText($config, "[wsl2]`r`nmemory=$memory`r`n")   # UTF-8 (BOM なし) で書く
}
wsl --shutdown   # 設定は VM の起動時に読まれるため、動いている VM を止める
wsl --install Ubuntu-26.04 --name $distro
```

`.wslconfig` は UTF-8（BOM なし）で保存します。
UTF-16 や BOM 付きで保存すると、設定が無視されます。
Windows PowerShell 5.1 の `Out-File` は UTF-16、`Set-Content -Encoding UTF8` は BOM 付きで書くので、上のコマンドは `[IO.File]::WriteAllText` を使っています。
既にあるファイルをメモ帳で直すときは、「名前を付けて保存」でエンコードに「UTF-8」を選びます。

インストールが終わると Ubuntu が起動するので、ユーザー名とパスワードを設定します。
この Ubuntu のターミナルは 3 で使うので、開いたままにします。

## 2. Docker Desktop の WSL Integration

1. Docker Desktop を起動します。
2. **Settings > Resources > WSL Integration** を開きます。
3. 1 で作ったインスタンス（`dev`）を有効にします。
4. **Apply & Restart** をクリックします。

## 3. Ubuntu の初期セットアップ（ブロック1）

Ubuntu のターミナルに次のブロックを貼り付けます。
Git の user.name と user.email が未設定のときだけ、その値を聞かれます。
sudo のパスワードは1回聞かれます。

``` bash
(
set -euo pipefail

# Git の名前とメールアドレス。設定済みなら聞かない
git_name=$(git config --global --get user.name || true)
git_email=$(git config --global --get user.email || true)
[ -n "$git_name" ] || read -rp 'Git の user.name: ' git_name
[ -n "$git_email" ] || read -rp 'Git の user.email: ' git_email

# 共通ワークスペース
sudo install -d -o "$(id -un)" -g "$(id -gn)" -m 755 /home/projects

# 基本パッケージ。extrepo は mise の apt リポジトリの登録が使う
sudo apt update
sudo apt upgrade -y
sudo apt install -y curl git wget unzip vim tree gnupg2 software-properties-common extrepo

# GitHub CLI の apt リポジトリ（公式の手順と同じ鍵と URL）
out=$(mktemp)
wget -nv -O"$out" https://cli.github.com/packages/githubcli-archive-keyring.gpg
sudo install -D -m 644 "$out" /etc/apt/keyrings/githubcli-archive-keyring.gpg
rm -f "$out"
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
  | sudo tee /etc/apt/sources.list.d/github-cli.list > /dev/null

# mise の apt リポジトリ
compgen -G '/etc/apt/sources.list.d/*mise.sources' > /dev/null || sudo extrepo enable mise

sudo apt update
sudo apt install -y gh mise

# Git
git config --global user.name "$git_name"
git config --global user.email "$git_email"
git config --global core.autocrlf input
git config --global core.fileMode true
git config --global core.symlinks true
git config --global alias.logs "log --pretty='format:%C(yellow)%h %C(green)%cd %C(cyan)%an %C(reset)%s %C(magenta)%d' --date=format:'%Y-%m-%d %H:%M:%S' --graph"
)
```

各ツールを入れる理由は次のとおりです。

- **mise**：Task、betterleaks、iwe、jq、Java（Amazon Corretto）、zizmor、Kiro CLI を、`mise.toml` と `mise.lock` で固定した版で入れます（[ADR-090](../adr/ADR-090-pin-cli-tools-with-mise-lock.md)）。これらは apt では入れません。
- **GitHub CLI（`gh`）**：Git の HTTPS の認証と、Issue や Pull Request の操作に使います。

途中で失敗すると、そこでブロックが止まります。
原因を直してから、同じブロックをもう一度貼り付けます（何度実行しても同じ結果になります）。

## 4. リポジトリのセットアップ（ブロック2）

Ubuntu のターミナルに次のブロックを貼り付けます。
`gh auth login` はワンタイムコードと URL を表示するので、ブラウザで開いてコードを入力します。
ブラウザが開かないときは、表示された URL を Windows のブラウザで開きます。
「Authenticate Git with your GitHub credentials?」と聞かれたら Yes を選びます。

実行する前に、`mise trust` が読む `mise.toml` の内容を確かめます。
`mise.toml` の `http:kiro-cli` が option を持つので、trust するまで mise は設定を読みません。
trust はパスごとに記録され、中身が変わっても取り消されません。

``` bash
(
set -euo pipefail
gh auth status > /dev/null 2>&1 || gh auth login --hostname github.com --git-protocol https --web
gh auth setup-git   # Git の HTTPS の認証を gh に任せる

cd /home/projects
[ -d spring-modulith-ai-dlc ] || gh repo clone yokozi-jp/spring-modulith-ai-dlc
cd spring-modulith-ai-dlc

mise trust
mise install --locked                  # mise.toml の道具を、mise.lock の版と checksum で検証して入れる
eval "$(mise activate bash --shims)"   # このブロックの中で task などを使えるようにする
task setup                             # Vite+、shims の PATH、Kiro の権限の雛形
. ~/.config/vite-plus/env              # task setup が入れた vp を、このブロックの PATH に足す
npm ci                                 # Lefthook と commitlint
task fe-install                        # フロントエンドの依存

# 確認。どれかが失敗するとブロックが止まる
[ -z "$(mise ls --current --missing)" ]   # mise.toml の道具がすべて入っている
docker info > /dev/null                   # Docker Desktop の WSL Integration が有効
java -version
node -v
task --version
kiro-cli --version
gh auth status
)
```

ブロックが終わったら、`exec bash -l` を実行するか、ターミナルを開き直します。
`task setup` が `~/.bashrc` と `~/.profile` に足した PATH の設定を、自分のシェルに反映するためです。
`task setup` の内容は次のとおりです。

- Vite+（`vp`）を入れます。Node.js と pnpm は Vite+ が用意するので、別に入れません。
- mise の shims を PATH の先頭に通す行を、`~/.bashrc` と `~/.profile` に足します。`~/.profile` は、`~/.bashrc` の後で `~/.local/bin` を先頭へ足すので、末尾でも shims を先頭へ足し直します。VS Code Server は、ログインシェルで作った PATH を拡張とターミナルに渡します。
- Kiro の権限の雛形を `~/.kiro/settings/permissions.yaml` に置きます。`permissions.yaml` か `permissions.json` がすでにあれば置かず、雛形との差分を表示します。

shims は、今いるディレクトリから上にたどって見つけた `mise.toml` の版を選びます。
そのため `task`、`betterleaks`、`iwe`、`java` は、`mise.toml` のあるチェックアウトの中でだけ動きます。
Gradle は `JAVA_HOME` が無ければ PATH の `java` を使うので、`JAVA_HOME` は設定しません。
VS Code の Java 拡張も、`JAVA_HOME` か PATH の `java` を使います。

Kiro CLI は起動時に自己更新を確かめます。
`kiro-cli settings app.disableAutoupdates true` で止めます。
版は `mise.toml` を書き換えて上げます。
`mise.toml` か `mise.lock` が変わったときは、`mise install --locked` だけを実行します。

## 5. VS Code で開く

Ubuntu のターミナルで、リポジトリの root から VS Code を開きます。
左下のステータスバーに `WSL: dev` と表示されれば、WSL に接続できています。
推奨拡張機能（`.vscode/extensions.json`）のインストールを促されたら、すべて入れます。

``` bash
cd /home/projects/spring-modulith-ai-dlc
code .
```

## 完了の目安

ブロック2が最後の `gh auth status` までエラーなしで終われば、道具と依存は入っています。
コードまで通ることを確かめたいときは、次を実行します。

``` bash
task check    # バックエンドの静的解析
task verify   # 静的解析と、隔離したテスト
```

## トラブルシューティング

ブロックが途中で失敗したら、原因を直してから、同じブロックをもう一度貼り付けます。
