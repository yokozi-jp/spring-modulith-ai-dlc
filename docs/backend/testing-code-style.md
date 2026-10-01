---
type: Convention
title: バックエンドのテストコードの書き方
description: バックエンドテストの命名、可視性、アサーション、Springコンテキスト、失敗診断、非同期待機の書き方を定め、テストコードを書く、または直すときに読む規約。
tags: [convention, backend, testing, spring]
---

# バックエンドのテストコードの書き方

テストは失敗対象と再現条件がレポートから分かるように書き、共有するSpringコンテキストを増やさない。
非同期処理は固定時間ではなく、期限付きの条件待機で検証する。

## 基本の書き方

- Google Java Formatに従い、`task be-format`で整形する。
- クラスとフィールドにはJavadocを付ける。
- `@Test`メソッドには`@DisplayName`で検証意図を書く。
- テストクラス、テストメソッド、ネスト型はパッケージプライベートにする。
- テストクラス名は単数形の`...Test`で終える。
- `@Disabled`には停止理由を指定する。
- JUnit AssertionsとAssertJのどちらを使ってもよいが、一つのテストクラス内では揃える。
- 一つのテストでは一つの振る舞いを検証し、その範囲で複数のアサーションを使ってよい。

## Springコンテキスト

フルの`@SpringBootTest`は、[SharedTestConfiguration](../../backend/src/test/java/com/example/demo/testkit/SharedTestConfiguration.java)を`@Import`するか、それを内蔵する合成アノテーションを使う。
テストごとの`@Import`や`@MockBean`で共有構成を分岐させない。
OIDCクライアント登録など複数のテストで使う差し替えは、共有構成へ集約する。

## 失敗時の診断情報

失敗メッセージには、該当する対象、入力、expected、actual、再現情報を残す。
対象にはレコードID、ファイルパス、リクエスト、イベントキーなどを指定する。
再現情報には乱数seed、期限、最後に観測した状態などを指定する。

- JUnitの等価比較ではexpectedとactualの引数を維持し、メッセージで対象と入力を補う。
- AssertJでは標準の差分を維持する`as(...)`を使い、標準の差分を置き換える必要がある場合だけ`withFailMessage(...)`を使う。
- `Optional`が空の場合は、対象と検索条件を持つ`AssertionError`などを送出する。
- プロパティベーステストでは、縮小された反例とseedを失敗ログから取得できるようにする。
- 修正方法をメッセージへ書くのは、修正方針が一つに決まるアーキテクチャ規則や禁止APIに限る。

```java
assertEquals(
    expected,
    actual,
    () -> "publicationId=" + publicationId + ", column=publication_date");
```

```java
assertThat(actual)
    .as("publicationId=%s の保存往復", publicationId)
    .isEqualTo(expected);
```

```java
final Instant actual =
    result.orElseThrow(
        () ->
            new AssertionError(
                "event_publication が見つからない: publicationId=" + publicationId));
```

## 非同期待機

固定時間を待つ`Thread.sleep()`を使わない。
イベントの発行と購読はSpring Modulithの`Scenario`で条件を待つ。
それ以外の非同期処理は、対象APIの期限付き条件待機か、JUnitの非プリエンプティブな`assertTimeout`を使う。
タイムアウト時には、対象ID、期待条件、期限、最後に観測した状態を出す。
`assertTimeoutPreemptively`は使わない。

機械的に判定できる規約と検査実装は[バックエンドのアーキテクチャテスト](architecture-tests.md)を参照する。
変更後は`task be-format`と`task be-lint`を実行する。
