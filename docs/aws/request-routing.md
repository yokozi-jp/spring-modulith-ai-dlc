---
type: Convention
title: AWSでのリクエストの入口とサービス間の経路
description: インターネットに公開するAPIへCloudFrontとWAFを置く条件、API Gatewayを使う条件と使い方、ロードバランサーの選び方、ECSのサービス間の通信方式を定める規約。AWSでSPAとAPIの公開経路を設計するとき、API Gatewayやロードバランサーを導入するか決めるとき、サービス間の呼び出しを追加するときに読む。
tags: [convention, aws, web-api, network, future-arch-guidelines]
---

# AWSでのリクエストの入口とサービス間の経路

インターネットに公開するAPIはCloudFrontとWAFを前段に置き、非公開のAPIにはCloudFrontを使わない。
ロードバランサーはALBを使い、API Gatewayは外部との境界で利用者単位のレート制限が要る場合に限って使う。
ECSのサービス間の通信は、原則としてALBを経由する。

## 前提

この文書は、入口の機能をAWSのどのサービスで実現するかを定める。
入口に持たせる機能と持たせない機能は[入口とAPIの機能配置](../web-api/edge-responsibilities.md)に従う。
SPAとAPIは[ADR-014](../adr/ADR-014-use-same-origin-spa-security-boundary.md)に従い同じオリジンで公開し、SPAの配信点は[フロントエンドのビルドと配信](../frontend/build-and-delivery.md)に従う。

CloudFront、API Gateway、ALBのどれにも、認証と認可、CORSを持たせない。
認証と認可はアプリケーションで行い（[ADR-007](../adr/ADR-007-session-based-auth-with-oidc-pkce.md)）、同じオリジンで公開するためCORSを有効にしない（ADR-014）。

## CloudFrontとWAF

- **一般の利用者向けにインターネットへ公開するAPI**：CloudFrontを使う。DDoS攻撃を防ぐ境界を、リージョン内ではなく利用者に近いエッジに置けるためである。キャッシュできる応答が少ない場合や、国内に限ったサービスでも導入を検討する。
- **取引先向けにインターネットへ公開するAPI**：SPAをCloudFrontで配信していれば、APIもCloudFrontを使う。そうでなければ、99.99%以上のSLAが求められる場合、不特定多数の取引先からアクセスされDDoS対策を強める必要がある場合、国外からのアクセスで地理的な応答性能が求められる場合のいずれかに当たるときにCloudFrontを使う。
- **非公開のAPI**（社内向け、VPC内の連携）：CloudFrontを使わず、ALBへ直接アクセスさせる。アクセス元が限られ、地理的なレイテンシの削減が効かないためである。

CloudFrontを使う場合は、次のとおりにする。

- SPAとAPIを同じディストリビューションからパスで振り分け、同じオリジンにする。
- 利用者ごとに見え方が変わる応答はキャッシュしない。APIの`Cache-Control`は[APIのリクエストヘッダーとレスポンスヘッダー](../web-api/headers.md)に従う。

インターネットに公開するAPIには、WAFとShield Standardを使う。
Shield Advancedは1年の契約と固定費がかかるため、費用対効果を見て導入を決める。

## API Gateway

- システム内のAPIの連携には使わない。構成要素と費用が増えるためである。
- システムの境界で、利用者やAPIアクセスキーの単位のレート制限が要る場合に使う。個別に実装するのが難しいためである。
- API Gatewayで要件を満たせない場合に限り、サードパーティのAPIゲートウェイの製品を検討する。

API Gatewayを使う場合は、次のとおりにする。

- WAFとの統合か、APIキーごとの使用量プランが必要ならREST APIを、どちらも不要ならHTTP APIを選ぶ。HTTP APIはWAFと統合できず、レート制限もアカウントとルートの単位に限られるためである。
- 統合のタイムアウト（既定で29秒）とペイロードの上限（10MB）に収まるかを確かめる。
- パスは`{proxy+}`でバックエンドへ通し、細かなパスの振り分けはバックエンドのアプリケーションで行う。
- OpenAPIの契約にAPI Gateway固有の拡張（`x-amazon-apigateway-integration`など）を入れず、API Gatewayの構築に使わない。契約はインターフェースの定義に保ち、ルーティングと統合はIaCで定義する。契約の扱いは[ADR-013](../adr/ADR-013-standardize-http-api-contracts.md)に従う。
- 入力の検証はAPI Gatewayで行わず、[APIの入力検証の配置](../web-api/validation.md)に従う。

## ロードバランサー

- ECSで動くWeb APIのロードバランサーには、ALBを使う。
- NLBは、低レイテンシが強く求められるなど特殊な条件がある場合に限って検討する。
- API GatewayからECSへ直接つなぎ、ロードバランサーを省く構成は通常選ばない。ロードバランサーより詳細なヘルスチェックができず、設定の項目が多く、タイムアウトに上限があるためである。
- ターゲットグループのヘルスチェックには、Spring Boot Actuatorのreadinessを使う（[APIの失敗のログレベルと監視](../web-api/logging-and-monitoring.md)）。

## ECSのサービス間の通信

- 原則としてALBを経由する。モジュラーモノリス（[ADR-001](../adr/ADR-001-adopt-spring-modulith-modular-monolith.md)）ではサービス間の通信量が費用やレイテンシの問題になりにくく、フロントエンドから呼ばれるサービスにはALBがあり経路を揃えられるためである。
- マイクロサービスに分け、サービス間の通信が非常に多くなった場合は、ECS Service Connectを使う。
- レイテンシを極限まで下げる要件がある場合に限り、ECS Service Discoveryを検討する。サービスを切り離すときのDNSの更新を含め、安定して動くかを検証してから採用する。
- VPC Latticeは通常使わない。

ECS Service Connectのプロキシは自動で再試行する。
使う場合は、プロキシの再試行とアプリケーションの再試行を重ねず、[ADR-019](../adr/ADR-019-define-resilience-and-capacity-guardrails.md)の再試行の回数と対象に合わせる。

## 出典

- フューチャー株式会社「AWS設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forAWS/aws_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
