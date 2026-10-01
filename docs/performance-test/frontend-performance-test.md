---
type: Convention
title: 画面の性能テスト
description: 画面の性能テストを行う条件、目標にする指標と閾値、計測ツールの選び方、画面の性能を改善する打ち手を定める規約。画面の表示速度や操作の応答性が要件になったとき、画面の性能テストを計画するときに読む。
tags: [convention, performance-test, frontend, web-vitals, future-arch-guidelines]
---

# 画面の性能テスト

画面の性能テストは、画面の体験が業務の価値に直結する場合に行い、業務システムでは必須にしない。
目標はLCPとINPに置き、モバイルとPCのそれぞれでページ読み込みの75パーセンタイルがLCP 2.5秒以内、INP 200ミリ秒未満を満たすことを基準にする。
画面のチューニングは、APIとDBの対策を検討した後に行う。

テスト種別の分け方、計画、準備の進め方はバックエンドと同じであり、[性能テストの計画](test-planning.md)に従う。

## 行う条件

- 社内や取引先向けの業務システムでは、APIのレスポンスタイムが支配的であるため、画面の性能テストを必須にしない。
- 一般消費者向けのサービスのように、表示の遅さや操作への反応の遅さが離脱や売上に直結する場合は、目標値を定めて行う。
- 業務システムでも、複雑な処理を持つ画面がある場合や、画面を含めたターンアラウンドタイムが要件になる場合は行う。

## 指標と閾値

web.devが定めるCore Web Vitalsのうち、次の二つを目標にする。

- **LCP（Largest Contentful Paint）**：ページ内の最も大きなコンテンツが描画されるまでの時間。
  読み込みの開始から2.5秒以内にする。
- **INP（Interaction to Next Paint）**：クリック、タップ、キー入力から、次の描画が反映されるまでの時間。
  ページ滞在中の最も遅い操作に近い値が報告される。
  200ミリ秒未満にする。

モバイルとPCのそれぞれで、ページ読み込みの75パーセンタイルが閾値を満たすことを基準にする。

次の指標は、性能テストの目標にしない。

- **CLS（Cumulative Layout Shift）**：表示のずれの累積量。
  体験の品質としては重要だが、速度の指標ではないため、UIの品質として扱う。
- **FCP（First Contentful Paint）**：最初のコンテンツの描画は、ローディングの表示でも発生して体感と離れるため、LCPで代える。
- **TBT（Total Blocking Time）**：ラボ環境でしか測れないため、実際の利用者の環境でも測れるINPで代える。
- **TTFB（Time to First Byte）**：サーバーとネットワークの指標であるため、[性能テストの目標値](performance-targets.md)のAPIの処理時間として扱う。

## 計測ツールの選び方

LCPとINPを測れるツールから、テスト種別で選ぶ。

- **単一の画面を測るボリュームテストだけの場合**：Chromeの開発者ツールやCLIで動き、スコアと改善策を示すラボ計測のツール（Lighthouseなど）を使う。
  CIで継続して測る場合は、CIへの組み込みを公式に提供するものを選ぶ。
- **ラボ計測で原因が分からない場合**：実機と実回線で測れ、通信のウォーターフォールを詳しく分析できるツール（WebPageTestなど）を検討する。
- **同時アクセスをかけるラッシュテストも行う場合**：ブラウザを操作しながら負荷をかけられ、バックエンドの負荷ツールと統合できるツールを選ぶ。
  バックエンドの負荷ツールに組み込まれた機能を優先し、導入と維持の費用を抑える。

ブラウザを操作するテスト基盤の追加は[ADR-027](../adr/ADR-027-adopt-frontend-testing-stack.md)の範囲を超えるため、採用するツールはADRに記録する。
バックエンドの負荷ツールの選び方は[負荷ツールとテストスクリプト](test-scripts.md)に従う。

## 画面の性能を改善する打ち手

システム全体では、アプリケーションのロジックとDBがボトルネックになることが多い。
それらの対策を検討した後に、画面の転送量と通信を次の順で見直す。

- **静的ファイルの圧縮**：HTML、CSS、JavaScriptは配信点で圧縮して送る。
  対応するブラウザにはgzipより圧縮率の高いBrotliを使い、それ以外にはgzipを使う。
- **画像**：形式、解像度ごとの出し分け、初期表示の領域の外にある画像の遅延読み込みは、[画像とアイコン](../frontend/images-and-icons.md)に従う。
- **minifyとブラウザのキャッシュ**：[フロントエンドのビルドと配信](../frontend/build-and-delivery.md)に従う。
- **CDN**：静的ファイルを利用者に近い配信点にキャッシュする。
- **HTTP/2とHTTP/3**：配信点で両方を有効にする。
  HTTP/2は一つの接続で複数のファイルを並行して送れるが、パケットが失われると同じ接続のすべての通信が止まるため、この問題のないHTTP/3を併せて使う。
- **ファイルの粒度**：CSSとJavaScriptは単一の巨大なファイルにせず分割して配信する。
  数百個まで分けると圧縮の効率が下がるため、分けすぎない。

APIの応答の圧縮は[入口とAPIの機能配置](../web-api/edge-responsibilities.md)に従う。

## 出典

- フューチャー株式会社「Appendix（はじめての性能テスト）」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forPerformanceTest/appendix.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- フューチャー株式会社「はじめての性能テスト」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forPerformanceTest/performance_test.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
