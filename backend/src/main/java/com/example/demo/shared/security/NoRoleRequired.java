package com.example.demo.shared.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * application の CommandHandler と QueryService のメソッドが、役割を確かめないと決めたことと、その根拠を表す（ADR-129）。
 *
 * <p>役割で許すメソッドには {@code @PreAuthorize} を付け、役割を問わないメソッドにはこの注釈を付けて、どちらかを決めたことを ArchUnit
 * の規則で確かめる。メソッドにだけ付ける。クラスに付けられると、後から足したメソッドも黙って役割を問わないことになるため。
 *
 * <p>実行時には何もしない。読むのは ArchUnit だけで、保持を RUNTIME にするのは、{@link Basis} をクラスファイルだけに残すと JaCoCo
 * のクラスごとの検査が、読み込まれない列挙型を未実行として失敗にするため。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NoRoleRequired {

  /** 役割を問わずに呼んでよい根拠。 */
  Basis value();

  /** 役割を問わずに呼んでよい根拠。 */
  enum Basis {

    /** 見える範囲を行レベルセキュリティでテナントに絞る。テナントの全員に許す。 */
    TENANT_SCOPED,

    /** 呼び出し側が本人の利用者の ID を渡し、SQL がその ID で絞る。 */
    CALLER_PASSES_OWN_USER_ID,

    /** Listener など、認証のない呼び出しから呼ぶ。 */
    CALLED_WITHOUT_AUTHENTICATION,

    /** ログインの途中で呼び、役割もテナントもまだない。 */
    BEFORE_LOGIN_COMPLETES
  }
}
