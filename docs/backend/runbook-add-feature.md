---
type: Runbook
title: バックエンドの機能追加時の確認
description: 新しい機能モジュールを追加するときの配置の順序と、アーキテクチャテストと静的解析の実行コマンドを示す。機能モジュールを新しく作るとき、機能追加後に検証するときに読む。
tags: [runbook, backend, spring-modulith]
---

# バックエンドの機能追加時の確認

機能モジュールを作り、公開契約、Domain、Application、Presentation、Infrastructure の順に配置を決める。
最後にアーキテクチャテストと静的解析を実行する。
各層に置くものは [バックエンドの層の責務](layers.md) に、モジュールルートの規則は [バックエンドアーキテクチャ](architecture.md) に示す。

## 配置の順序

新しい機能を追加するときは、次の順序で配置を決める。

1. Spring Modulith の機能モジュール名を決め、`com.example.demo.<feature>` を作る。
2. 他モジュールへ公開する必要がある契約だけをモジュールルートへ置く。
3. 業務状態と業務規則を Domain に置く。
4. ユースケースの進行とトランザクション境界を Application に置く。
5. HTTP 固有の型を Presentation に置く。
6. DB、メッセージブローカー、外部 API 固有の型を対応する Infrastructure Adapter に置く。
7. 作成した各 Java パッケージへ `@NullMarked` の `package-info.java` を追加する。
8. アーキテクチャテストと対象機能のテストを実行する。

## 検証コマンド

アーキテクチャテストは次のコマンドで実行できる。

``` bash
cd backend
./gradlew test \
  --tests com.example.demo.architecture.ApplicationModuleArchitectureTest \
  --tests com.example.demo.architecture.GeneralCodingRulesArchTest \
  --tests com.example.demo.architecture.PackageByFeatureOnionArchitectureTest
```

テストの内容は [バックエンドのアーキテクチャテスト](architecture-tests.md) に示す。

静的解析はワークスペースルートで次のコマンドを実行する。

``` bash
task be-lint
```
