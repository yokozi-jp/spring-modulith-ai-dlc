package com.example.demo.error;

/**
 * 対象のリソースが存在しないことを表す例外。API では 404 Problem Details へ変換する。
 *
 * <p>メッセージにはテーブル名などの調査用の情報だけを入れ、キーの値や個人情報を入れない。
 */
public class ResourceNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** 見つからなかった内容を表すメッセージを受け取る。 */
  public ResourceNotFoundException(final String message) {
    super(message);
  }
}
