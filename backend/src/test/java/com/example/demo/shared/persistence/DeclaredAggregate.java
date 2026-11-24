package com.example.demo.shared.persistence;

import java.util.List;

/**
 * 集約の宣言のうち、DB の外部キーと突き合わせる部分だけを、{@code スキーマ.テーブル}の名前で表す。
 *
 * <p>{@link AggregateTable} と {@link ChildTable} の読み出しは package-private
 * なので、別のパッケージの検査が宣言を読めるよう、この型に写す。
 *
 * @param root ルートのテーブル。{@code スキーマ.テーブル}
 * @param children 子のテーブル
 */
public record DeclaredAggregate(String root, List<DeclaredChild> children) {

  /**
   * 子のテーブルの宣言。
   *
   * @param table 子のテーブル。{@code スキーマ.テーブル}
   * @param parentKey 親の内部の主キーを持つとして宣言した列
   */
  public record DeclaredChild(String table, String parentKey) {}
}
