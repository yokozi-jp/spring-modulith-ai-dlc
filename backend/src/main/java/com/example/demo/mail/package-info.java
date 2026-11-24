/**
 * 利用者へメールを送る機能モジュール。SES v2 の API で送る外部システムの Client を持つ（ADR-125）。
 *
 * <p>メールを送る機能はまだなく、ほかのモジュールとつなぐイベントも {@code MailQueries} もまだ置かない。
 */
@ApplicationModule(allowedDependencies = {})
@NullMarked
package com.example.demo.mail;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
