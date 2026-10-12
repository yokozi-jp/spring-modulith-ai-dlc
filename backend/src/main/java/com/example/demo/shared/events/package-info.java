/**
 * イベントのリスナーが使う、失敗したイベント出版の再投入の語彙を置く（ADR-079）。リスナーの再投入の方針を指定する {@link
 * com.example.demo.shared.events.ResubmissionPolicy} である。
 *
 * <p>どの層からも使ってよい。業務の概念を置かず、Spring Modulith や jOOQ に依存しない。
 */
@NamedInterface("events")
@NullMarked
package com.example.demo.shared.events;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
