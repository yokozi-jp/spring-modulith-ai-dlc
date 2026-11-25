package com.example.demo.shared.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * application の CommandHandler と QueryService のメソッドが、役割を確かめないと決めたことを表す（ADR-129）。
 *
 * <p>実行時には何もしない。役割で許すメソッドには {@code @PreAuthorize} を付け、役割を問わないメソッドにはこの注釈を付けて、どちらかを決めたことを ArchUnit
 * の規則で確かめる。クラスに付けると、そのクラスのすべての public メソッドの宣言になる。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface NoRoleRequired {

  /**
   * 役割を問わずに呼んでよい理由。空にしない。
   *
   * <p>例：「ログインの前に呼ぶ」「Listener から呼ぶ。認証がない」「見える範囲は SQL で絞る」。
   */
  String reason();
}
