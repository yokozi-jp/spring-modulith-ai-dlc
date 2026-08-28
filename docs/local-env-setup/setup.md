# 開発環境構築

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
> ```ini
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

```cmd
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

```cmd
wsl -d <インスタンス名>
```

### 4. 初期セットアップ

```bash
# 共通ワークスペース作成
sudo mkdir -p /home/projects
sudo chown -R $USER:$USER /home/projects
sudo chmod -R 755 /home/projects

# 基本パッケージインストール
sudo apt update && sudo apt upgrade -y
sudo apt install -y curl git wget unzip jq vim tree gnupg2 software-properties-common make

# Git基本設定（⚠️ 以下2行は自分の名前・メールアドレスに書き換えてから実行してください）
# git config --global user.name "Your Name"
# git config --global user.email "you@example.com"

git config --global core.autocrlf input
git config --global core.fileMode true
git config --global core.symlinks true
git config --global alias.logs "log --pretty='format:%C(yellow)%h %C(green)%cd %C(cyan)%an %C(reset)%s %C(magenta)%d' --date=format:'%Y-%m-%d %H:%M:%S' --graph"
```

### 5. GitHub CLI のインストールと認証

```bash
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

```bash
gh auth login
```

以下のように選択してください：

```
? Where do you use GitHub? GitHub.com
? What is your preferred protocol for Git operations on this host? HTTPS
? Authenticate Git with your GitHub credentials? Yes
? How would you like to authenticate GitHub CLI? Login with a web browser
```

ブラウザが開くので、画面の指示に従って認証を完了してください。

認証完了後、Git の認証を GitHub CLI に委任します：

```bash
gh auth setup-git
```

### 6. リポジトリのクローン

```bash
cd /home/projects
gh repo clone yokozi-jp/spring-modulith-ai-dlc
cd spring-modulith-ai-dlc
```

### 7. セットアップスクリプトの実行

```bash
cd /home/projects/spring-modulith-ai-dlc
make setup
source ~/.bashrc
```

### 8. Go と betterleaks（シークレットスキャナ）のセットアップ

pre-commit フックでのシークレットスキャンに [betterleaks](https://github.com/betterleaks/betterleaks) を使用します。betterleaks は Go 製のため、先に Go をインストールします。

```bash
# Go の公式 tarball を取得して /usr/local に展開
cd /tmp
curl -fsSLO https://go.dev/dl/go1.27.0.linux-amd64.tar.gz
sudo rm -rf /usr/local/go && sudo tar -C /usr/local -xzf go1.27.0.linux-amd64.tar.gz

# PATH を通す（~/.bashrc に追記）
echo 'export PATH=$PATH:/usr/local/go/bin:$HOME/go/bin' >> ~/.bashrc
source ~/.bashrc
go version
```

`go version` で `go1.27.0`（インストールしたバージョン）が表示されれば OK です。

続けて betterleaks をインストールします。

```bash
go install github.com/betterleaks/betterleaks@latest
betterleaks version
```

`$HOME/go/bin` は上記で PATH に追加済みのため、`betterleaks` コマンドがそのまま使えます。

### 9. Git hooks のセットアップ

```bash
cd /home/projects/spring-modulith-ai-dlc
npm install
```

これにより Lefthook が有効化されます。以降、コミット時に pre-commit フックが実行され、ステージした変更を betterleaks がシークレットスキャンします。

### 10. VSCode から WSL への接続

1. Windows 側で VSCode を起動
2. `F1` キーを押してコマンドパレットを開く
3. `WSL: Connect to WSL using Distro...` を選択
4. 手順 1 で作成したインスタンス名を選択

VSCode が WSL モードで再起動し、左下のステータスバーに `WSL: <インスタンス名>` と表示されれば接続完了です。

### 11. VSCode 拡張機能のインストール

`.vscode/extensions.json` に記載されている推奨拡張機能をインストールしてください。

### 12. 動作確認

```bash
docker info
java -version
node -v
gh auth status
bun --version
betterleaks version
```

エラーなく各ツールの情報が表示されれば環境構築完了です。
