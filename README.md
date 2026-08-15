<div id="top"></div>

# spring-modulith-ai-dlc

## 使用技術一覧

<p style="display: inline">
  <!-- バックエンド -->
  <img src="https://img.shields.io/badge/-Java%2025-007396.svg?logo=openjdk&style=for-the-badge">
  <img src="https://img.shields.io/badge/-Spring%20Boot%204.1-6DB33F.svg?logo=spring-boot&style=for-the-badge">
  <img src="https://img.shields.io/badge/-Spring%20Modulith-6DB33F.svg?logo=spring&style=for-the-badge">
  <!-- フロントエンド -->
  <img src="https://img.shields.io/badge/-TypeScript-3178C6.svg?logo=typescript&style=for-the-badge&logoColor=white">
  <img src="https://img.shields.io/badge/-VitePlus-646CFF.svg?logo=vite&style=for-the-badge&logoColor=white">
  <img src="https://img.shields.io/badge/-pnpm-F69220.svg?logo=pnpm&style=for-the-badge&logoColor=white">
  <!-- ミドルウェア -->
  <img src="https://img.shields.io/badge/-PostgreSQL-4169E1.svg?logo=postgresql&style=for-the-badge&logoColor=white">
  <img src="https://img.shields.io/badge/-Redis-DC382D.svg?logo=redis&style=for-the-badge&logoColor=white">
  <!-- インフラ -->
  <img src="https://img.shields.io/badge/-Docker-1488C6.svg?logo=docker&style=for-the-badge">
  <img src="https://img.shields.io/badge/-Amazon%20AWS-232F3E.svg?logo=amazon-aws&style=for-the-badge">
  <img src="https://img.shields.io/badge/-GitHub%20Actions-2088FF.svg?logo=github-actions&style=for-the-badge&logoColor=white">
</p>

## 目次

1. [プロジェクトについて](#プロジェクトについて)
2. [環境](#環境)
3. [ディレクトリ構成](#ディレクトリ構成)
4. [開発環境構築](#開発環境構築)
5. [開発コマンド](#開発コマンド)
6. [トラブルシューティング](#トラブルシューティング)

## プロジェクトについて

Spring Modulith を用いたモジュラーモノリスアーキテクチャのサンプルプロジェクト。

## 環境

| 言語・フレームワーク | バージョン |
| --- | --- |
| Java (Amazon Corretto) | 25 |
| Spring Boot | 4.1.0 |
| Spring Modulith | 2.1.0 |
| TypeScript | 7.0.x |
| VitePlus | latest |
| pnpm | 11.21.0 |
| PostgreSQL | 18 |
| Redis | 7.x |
| Bun (AI-DLC ランタイム) | 1.3.14 |

その他のパッケージのバージョンは `backend/build.gradle` と `frontend/package.json` を参照してください。

<p align="right">(<a href="#top">トップへ</a>)</p>

## ディレクトリ構成

```
.
├── backend/          # Spring Boot アプリケーション
├── frontend/         # VitePlus + TypeScript フロントエンド
├── docker/           # Docker 関連ファイル
├── infrastructure/   # インフラ定義
├── docs/             # ドキュメント
│   ├── local-env-setup/  # 開発環境構築手順・スクリプト
│   └── aidlc-setup/      # AI-DLC セットアップ手順
├── aidlc/            # AI-DLC ワークスペース（自動生成）
├── .kiro/            # Kiro 設定
├── .vscode/          # VSCode 設定
└── Makefile          # 開発コマンド定義
```

<p align="right">(<a href="#top">トップへ</a>)</p>

## 開発環境構築

[開発環境構築ガイド](docs/local-env-setup/setup.md) を参照してください。

### AI-DLC のセットアップ

[AI-DLC セットアップ](docs/aidlc-setup/setup.md) を参照してください。

<p align="right">(<a href="#top">トップへ</a>)</p>

## 開発コマンド

ビルド、テスト、SBOM 生成などの各種コマンドは [`Makefile`](Makefile) にまとめています。

```bash
make <ターゲット名>
```

## トラブルシューティング

（随時追記）

<p align="right">(<a href="#top">トップへ</a>)</p>
