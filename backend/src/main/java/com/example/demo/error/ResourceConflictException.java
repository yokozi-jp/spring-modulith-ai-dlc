package com.example.demo.error;

/**
 * 他の処理による更新やロックと競合したことを表す例外。API では 409 Problem Details へ変換する。
 *
 * <p>メッセージにはテーブル名などの調査用の情報だけを入れ、キーの値や個人情報を入れない。
 */
public class ResourceConflictException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** 競合の内容を表すメッセージを受け取る。 */
  public ResourceConflictException(final String message) {
    super(message);
  }

  /** 競合の内容を表すメッセージと、競合の原因になった例外を受け取る。 */
  public ResourceConflictException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
