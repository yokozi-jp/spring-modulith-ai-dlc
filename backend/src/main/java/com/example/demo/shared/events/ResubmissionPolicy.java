package com.example.demo.shared.events;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 失敗したイベント出版を自動で再投入するときの、リスナーの再投入の方針の名前を指定する（ADR-079）。
 *
 * <p>{@code @ApplicationModuleListener} を付けた {@code <Event>Listener} の {@code on} に付ける。名前は {@code
 * event-publication.resubmission.policies} のキーにする。付けないリスナーは {@code default} の方針で再投入する。
 * 設定にない名前を指定すると、起動に失敗する。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ResubmissionPolicy {

  /** 方針の名前。 */
  String value();
}
