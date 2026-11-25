package com.example.demo.shared.persistence;

import java.util.List;

/**
 * 集約の宣言のうち、DB の外部キーと一意索引と突き合わせる部分だけを、{@code スキーマ.テーブル}と列の名前で表す。
 *
 * <p>{@link AggregateTable} と {@link ChildTable} の読み出しは package-private
 * なので、別のパッケージの検査が宣言を読めるよう、この型に写す。
 *
 * @param root ルートのテーブル。{@code スキーマ.テーブル}
 * @param key ルートを一つに決める一意の列
 * @param children 子のテーブル
 * @param duplicateKey 重複を許す一意索引の列。{@link AggregateTable#of} で宣言した集約は空
 */
public record DeclaredAggregate(
    String root, String key, List<DeclaredChild> children, List<String> duplicateKey) {

  public DeclaredAggregate {
    children = List.copyOf(children);
    duplicateKey = List.copyOf(duplicateKey);
  }

  /**
   * 子のテーブルの宣言。
   *
   * @param table 子のテーブル。{@code スキーマ.テーブル}
   * @param parentKey 親の内部の主キーを持つとして宣言した列
   * @param archive 削除する前の行を写す履歴の表。{@code スキーマ.テーブル}
   */
  public record DeclaredChild(String table, String parentKey, String archive) {}
}
