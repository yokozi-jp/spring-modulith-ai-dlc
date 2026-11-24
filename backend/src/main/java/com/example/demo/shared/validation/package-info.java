/**
 * 入力の検査に使う Bean Validation の制約を置く。メールアドレスの形式の検査（{@link
 * com.example.demo.shared.validation.MailAddress}）である。
 *
 * <p>どの層からも使ってよい。業務の概念を置かない。
 */
@NamedInterface("validation")
@NullMarked
package com.example.demo.shared.validation;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
