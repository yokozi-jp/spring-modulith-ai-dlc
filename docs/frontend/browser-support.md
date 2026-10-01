---
type: Convention
title: 対応ブラウザとWeb機能の採用基準
description: サポートするブラウザとバージョンの決め方と、BaselineによるWeb platform機能の採否を定める規約。対応ブラウザを決めるとき、新しいHTML、CSS、JavaScriptの機能を使うか判断するときに読む。
tags: [convention, frontend, browser, baseline, future-arch-guidelines]
---

# 対応ブラウザとWeb機能の採用基準

対応ブラウザはコアブラウザセットから必要最小限に絞り、セキュリティ更新が続くバージョンだけを対象にする。
Web platformの機能はBaselineの状態で採否を決め、Widely availableと、Experimentalでない Newly availableを使う。
ExperimentalのNewly availableとLimited availabilityの機能は本番のコードで使わない。

## 対応ブラウザ

**コアブラウザセット**：WebDX Community Groupが定める、Baselineの判定対象のブラウザ群。Chrome（デスクトップ、Android）、Edge（デスクトップ）、Firefox（デスクトップ、Android）、Safari（macOS、iOS）からなる。

対応ブラウザは、利用者のデバイスとOSを洗い出してから、次の基準で絞る。

- Google Chromeは対象に含める。
- Windowsでは、Chromiumベースで検証が重複するMicrosoft Edgeを対象から外せないか検討する。業務PCの標準ブラウザに指定されている場合は含める。
- iOSでは、標準ブラウザのSafariを対象に含める。
- macOSでは、Chromeだけに絞れないか検討する。
- Firefoxは、利用者の要件がなければ対象から外せないか検討する。
- セキュリティ更新が止まったOSと、そのOSでしか動かないブラウザのバージョンは対象にしない。

対応ブラウザは、CSP Level 3の `style-src-elem` と `style-src-attr` に対応している必要がある。
対応していないブラウザでは、Base UIのinline style要素が拒否される（[ADR-030](../adr/ADR-030-allow-base-ui-inline-style-elements.md)）。

## サポートバージョン

**エバーグリーンブラウザ**：利用者が意識せずに最新版へ自動更新されるブラウザ。

- デスクトップのエバーグリーンブラウザは、最新版と一つ前のバージョンを対象にする。
- デスクトップのFirefoxを対象にする場合は、最新版または延長サポート版（ESR）を対象にする。
- AndroidのChromeは最新版だけを対象にする。
- iOSのSafariはiOSのバージョンと連動するため、検証機が対応するiOSのバージョンから下限を決める。
- 業務アプリケーションでは、セキュリティ更新が続くバージョンだけを対象にし、最終的な範囲を導入先のサポートポリシーと導入端末から決める。

## Web機能の採用基準

**Baseline**：Web platformの機能がコアブラウザセットで相互運用できるかを示す指標。次の三つに分かれる。

- **Widely available**：全コアブラウザで使えるようになってから30か月以上経った機能。
- **Newly available**：全コアブラウザの最新安定版で使えるようになってから30か月未満の機能。
- **Limited availability**：一部のコアブラウザでしか使えない機能。

**Experimental**：実装が一つのレンダリングエンジンにしかない、設定やflagを変えないと動かない、または仕様が後方互換性なく変わり得る機能にMDNが付ける印。

機能の採否は次のとおりにする。

- Widely availableの機能は使ってよい。
- ExperimentalでないNewly availableの機能は使ってよい。
- ExperimentalのNewly availableの機能とLimited availabilityの機能は、本番のコードで使わない。
- ExperimentalのNewly availableの機能のうち、fallbackがありリスクが低く、生産性や保守性を大きく改善するものに限り、ADRで個別に採否を判断する。

Baselineの状態は、MDN、Can I use、Web Platform Status（webstatus.dev）で確認する。

`vite.config.ts` に `build.target` を指定せず、Viteの既定のbuild targetを使う。

## 出典

- フューチャー株式会社「Webフロントエンド設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forWebFrontend/web_frontend_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
