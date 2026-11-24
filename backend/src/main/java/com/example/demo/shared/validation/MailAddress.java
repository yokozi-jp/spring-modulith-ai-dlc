package com.example.demo.shared.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Email;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * メールアドレスの形式の検査。{@code @Email} に加えて、フロントエンドの Zod の {@code z.email()} が通す形だけを通す。
 *
 * <p>OpenAPI には {@code format: email} が出て、Orval が {@code z.email()} を生成する。
 * サーバーの検査がクライアントより緩いと、フロントエンドを通さない API の呼び出しがクライアントの断る形を保存してしまうため、 {@link #PATTERN} で揃える。{@code
 * openapi/mail-address-cases.json} の {@code pattern} が {@code PATTERN} と Zod の {@code
 * z.regexes.email} に同じ文字列であることと、表の入力の判定を、両側のテストが検査する（ADR-123）。
 */
@Documented
@Email(regexp = MailAddress.PATTERN) @Constraint(validatedBy = {})
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface MailAddress {

  /** Zod 4 の {@code z.regexes.email} と同じ式。Zod を更新して式が変わったら、フロントエンドのテストが失敗する。 */
  String PATTERN =
      "^(?:[A-Za-z0-9_'+\\-]+\\.)*[A-Za-z0-9_'+\\-]*[A-Za-z0-9_+-]@(?:[A-Za-z0-9][A-Za-z0-9\\-]*\\.)+[A-Za-z]{2,}$";

  /** 検査に失敗したときの message の key。合成した {@code @Email} の message が使われるので、上書きしても効かない。 */
  String message() default "{jakarta.validation.constraints.Email.message}";

  /** Bean Validation の groups。 */
  Class<?>[] groups() default {};

  /** Bean Validation の payload。 */
  Class<? extends Payload>[] payload() default {};
}
