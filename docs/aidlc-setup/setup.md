# AI-DLC セットアップ

## 初回セットアップ

初めて環境を構築する場合は、リポジトリのクローンから行います。

```bash
cd /home/projects
gh repo clone awslabs/aidlc-workflows
cd aidlc-workflows
git checkout v2
```

フレームワークのファイルをプロジェクトにコピーします：

```bash
cp -r dist/kiro/aidlc/ ../spring-modulith-ai-dlc/aidlc/
cp -r dist/kiro/.kiro/ ../spring-modulith-ai-dlc/
cp dist/kiro/AGENTS.md ../spring-modulith-ai-dlc/AGENTS.md
```

## アップデート

すでに `aidlc-workflows` リポジトリをクローン済みの場合は、最新を取得してコピーし直します。

```bash
cd /home/projects/aidlc-workflows
git fetch
git pull
```

フレームワークのファイルを上書きコピーします：

```bash
cp -r dist/kiro/aidlc/ ../spring-modulith-ai-dlc/aidlc/
cp -r dist/kiro/.kiro/ ../spring-modulith-ai-dlc/
cp dist/kiro/AGENTS.md ../spring-modulith-ai-dlc/AGENTS.md
```
