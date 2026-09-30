---
type: Convention
title: バックエンドのテストコードの書き方
description: バックエンドのテストコードが静的解析（PMD、SpotBugs、Spotless）と ArchUnit の TestConventionsArchTest に通るための書き方（Javadoc、@DisplayName、可視性、命名、アサーション）と、ArchUnit の解析対象の限定を定める。
tags: [convention, backend, testing, archunit, static-analysis]
---

# バックエンドのテストコードの書き方

## コード規約

静的解析（[ruleset.xml](../../backend/config/pmd/ruleset.xml)、[test-ruleset.xml](../../backend/config/pmd/test-ruleset.xml)、SpotBugs、Spotless の Google Java Format）に通る形で書く。

- フォーマットは Google Java Format に従う。`task be-format` で整形し、`task be-lint` で確認する。
- クラスとフィールドには Javadoc を付ける（PMD `CommentRequired`）。`@Test` メソッドはパッケージプライベートにするので Javadoc は不要。
- すべての `@Test` メソッドに `@DisplayName` で検証意図を明記する。コメントや Javadoc はバイトコードに残らず ArchUnit で強制できないが、`@DisplayName` は実行時に残りレポートにも出るため、意図の記述はこちらに寄せる。
- テストクラス、テストメソッド、ネスト型はパッケージプライベートにする（`public` を付けない。JUnit 5 は package-private を実行する）。既存コードは意図を示すため `/* package */` の目印を添えている。
- JUnit のアサーションには失敗時メッセージを添える。メッセージは期待値を言い換えず、失敗対象と入力を補う（[失敗時の診断情報](testing-strategy.md)）。
- アサーションは JUnit の `Assertions` と AssertJ のどちらでもよいが、1つのテストクラス内では揃える。
- テストクラス名は `...Test`（単数）を接尾辞にする。`...Tests`（複数）にしない。合成アノテーションや拡張などテストでない補助クラスには付けない。
- 複数アサーションは許容される（PMD `UnitTestContainsTooManyAsserts` は無効化済み）。1テストで1つの振る舞いを検証する範囲にとどめる。

## ArchUnit

- ArchUnit は手書きのプロダクションコードだけを解析する。生成コード（`jooq` / `generated`）とテストコードは [ProductionCodeOnly](../../backend/src/test/java/com/example/demo/architecture/ProductionCodeOnly.java) で除外する。
- `@ArchTest` フィールドはルールの説明として命名するため、定数命名規則（UPPER_SNAKE）とは別扱いにしている（PMD `FieldNamingConventions` は無効化済み）。
- テストコード自身の規約は [TestCodeOnly](../../backend/src/test/java/com/example/demo/architecture/TestCodeOnly.java)（`ProductionCodeOnly` の対）で対象を反転し、[TestConventionsArchTest](../../backend/src/test/java/com/example/demo/architecture/TestConventionsArchTest.java) で強制する。コメントや Javadoc は ArchUnit では検査できないため、機械判定できる次の項目だけを扱う。
  - すべての `@Test` メソッドに `@DisplayName` があること。
  - `@Test` メソッドと、それを含むクラスが `public` でないこと。
  - `@Test` を含むクラス名が `Test` で終わること。
  - 直接 `@SpringBootTest` を付けたクラスが `@Import(SharedTestConfiguration.class)` を持つこと（合成アノテーション経由でもよい）。
  - `assertTimeoutPreemptively` を呼ばないこと。
  - テストコードでレガシー日時型を使わないこと（禁止型を参照する `architecture` パッケージ自身は除外）。
  - `@Disabled` に理由（`value`）があること。
