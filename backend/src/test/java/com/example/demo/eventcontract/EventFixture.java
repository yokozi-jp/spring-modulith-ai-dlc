package com.example.demo.eventcontract;

/**
 * 1 件の fixture。ファイル名は型と版から {@link #fileName} で作る。
 *
 * @param typeName ファイル名が示すイベントの型の完全な名前
 * @param version ファイル名が示す版（1 から）
 * @param json ファイルの中身
 */
/* package */ record EventFixture(String typeName, int version, String json) {

  /** 型と版から fixture のファイル名 {@code <型の完全な名前>.v<n>.json} を作る。 */
  /* package */ static String fileName(final String typeName, final int version) {
    return typeName + ".v" + version + ".json";
  }
}
