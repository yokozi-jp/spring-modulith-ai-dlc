---
type: Convention
title: バックエンドの型の設計規約
description: 場合分け、識別子、不正な値を作れない形、全域な型、生型、外部データの型への変換、形の正本からの導出、キャスト、switch の網羅、null の扱いを、型でコンパイラに不正な状態を拒否させる観点で定める。Java のコードで型を設計するとき、sealed interface や record を選ぶとき、キャストや switch の default を書こうとしたときに読む。
tags: [convention, backend, java, type-system]
---

# バックエンドの型の設計規約

不正な状態を作れない型を選び、コンパイラに拒否させる。
場合分けは sealed interface と record で表し、switch の `default` と安全でないキャストを書かない。
外部のデータは境界で一度だけ型に変換し、内部では型を信頼する。
Lombok、record の使い分け、例外、ロギング、依存の注入は[バックエンドの Java 実装規約](java-coding.md)に、時刻は[日時の索引](../datetime/index.md)に従う。

## 場合分けは sealed interface と record で表す

取りうる場合が閉じた集合の値は、sealed interface と、その許可された record で表す。
型が場合を区別するので、`@Nullable` の component を並べて「どれかが入る」ことを約束にする record は作らない。

```java
sealed interface ShipmentResult {
  record Shipped(ShipmentId id) implements ShipmentResult {}
  record Rejected(String reason) implements ShipmentResult {}
}
```

集約の状態遷移には使わない。
集約の `status` は変わるフィールドとして持つ（[集約](class-roles/aggregate.md)）。

## 取り違えを防ぐ型は Domain の中に置く

意味の違う値は、Domain の中で専用の値オブジェクトにする（`OrderId`、`Money`）。
値の形だけで不変条件がないなら、本体の空の `public record OrderId(UUID value) {}` にする。
不変条件があるなら、コンパクトコンストラクタ一箇所で検査する（[値オブジェクト](class-roles/value-object.md)）。

Command、Result、Request、Response、モジュールルートの record は、値オブジェクトを持たず標準型で表す。
値オブジェクトへの変換は CommandHandler の `handle` で行う（[Command](class-roles/command.md)）。

record は component を位置で受けるため、同じ型の component を並べると取り違えがコンパイルを通る。
`Position(int line, int column)` に `new Position(column, line)` と書いても通る。
取り違えると業務上の意味が変わる同じ型の値は、別の値オブジェクトに分ける。

## 不正な値を作れない形にする

二つの値の関係を検査する代わりに、関係が壊れない持ち方を選ぶ。
終了時刻が開始時刻より前という状態は、開始と長さで持てば作れず、検査は長さが負でないことの一つに減る。

```java
record Period(Instant start, Duration length) {
  Period {
    if (length.isNegative()) {
      throw new IllegalArgumentException("length=" + length);
    }
  }

  Instant end() {
    return start.plus(length);
  }
}
```

型だけで防げない条件は、コンパクトコンストラクタ一箇所に集める。
コメントに書いた不変条件に頼らない。

空でない列は、先頭と残りの組にすると空の値が作れない。

```java
record NonEmptyList<E>(E head, List<E> tail) {
  NonEmptyList {
    tail = List.copyOf(tail);
  }
}
```

## 全域な型を保つ

すべての操作が全域であるあいだは、`List<T>` のままにする。
空の列の合計は 0 で、そのまま意味が通る。

緩い型が使う側に嘘を強いるときだけ、型を強くするか、結果を `Optional` にする。
目印は、`getFirst()`、`get(0)`、引数なしの `Optional.orElseThrow()` と、「ありえない」と書いた例外である。
`getFirst()` は空のリストで `NoSuchElementException` を投げ、その例外の生成と引数なしの `orElseThrow()` は ArchUnit が禁じる（[バックエンドの Java 実装規約](java-coding.md)）。

```java
Optional<Session> newest(List<Session> sessions) {
  return sessions.stream().findFirst();
}
```

## 生型を使わない

生型（`List` や `Map` の型引数なし）は、型引数の検査を捨てる。
ビルドは `-Xlint:rawtypes,unchecked` で警告を出す。
戻り値は型引数付きの変数で受ける。

## 外部のデータは境界で型に変換する

HTTP のボディ、JSON、メッセージ、永続化した JSON、環境変数は、入る場所で名前のある型に変換する。
`Object` や `Map<String, Object>` は、その変換で終わらせ、内部へ渡さない。

HTTP のボディは、Request の record へ Jackson で束縛し、Bean Validation の制約で形式を検証する（[Request](class-roles/request.md)）。
形式の検証を手書きの if の連なりにしない。
Command に変換したあとは、再び検証せず、型を信頼する。

永続化した JSON は、バージョンを持つ形にし、解析の失敗を扱う。

## 形の正本から導く

形の正本が別にあるなら、同じ形の型を手書きしない。

- **DB の列**：jOOQ の生成型が正本で、Repository は `Records.mapping(Order::restore)` のようにコンパイルで列の数と型を検査して変換する（[jOOQ Repository](class-roles/jooq-repository.md)）。
- **HTTP の契約**：このバックエンドはコードが先で、Request と Response の record から OpenAPI を生成する。OpenAPI の定義から型を手書きしない。

## 安全でないキャストを書かない

`(T) obj` と `@SuppressWarnings("unchecked")` は、検査していない主張を黙らせる。
`(List<User>) data` は、キャストした時点では失敗せず、離れた場所で要素を読んだときに `ClassCastException` になる。
型パターンの `instanceof` か、sealed 型の switch で受ける。

```java
if (data instanceof User user) {
  handle(user);
}
```

キャストを消せないときは、コンパイラが推論できない理由を見る。
場合の区別がないなら sealed interface にし、元の型が広すぎるなら狭め、型のない境界なら record へ変換する。

## switch を網羅させる

sealed 型の switch に `default` を書かない。
`default` があると、バリアントを足してもコンパイルが通り、新しい場合を黙って受ける。

```java
double area(Shape s) {
  return switch (s) {
    case Circle c -> Math.PI * c.radius() * c.radius();
    case Rect r -> r.width() * r.height();
  };
}
```

パターンを使う switch は、文でも網羅を検査される。
パターンを使わない enum の switch 文は検査されず、定数を足しても case の欠落がコンパイルを通る。
enum は switch 式で書く。

値の条件が要る場合は `case Rect r when r.width() == 0 ->` のように `when` を使い、同じ型の無条件の case を最後に置く。

## null を型で示す

各パッケージの `package-info.java` に `@NullMarked` を付け、null を取りうる箇所だけ `@Nullable` を付ける。
`Optional` は戻り値だけに使い、フィールドと引数には使わない。
フィールドの `Optional` は、null と空の二つの「ない」を作る。

## 検査

ビルドが検査するのは、生型と非検査の警告（`-Xlint:rawtypes,unchecked`）と、`@NullMarked` のコードの null の違反と付け忘れ（Error Prone の NullAway と `RequireExplicitNullMarking`）である。
sealed の使い方、`default` の禁止、キャストの禁止は、機械では検査していない。
レビューで確かめる。
