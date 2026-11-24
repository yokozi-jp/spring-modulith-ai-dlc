/**
 * Spring Modulith のイベント出版のレジストリの、失敗した出版の定期の再投入と手での 1 回の再投入を置く（ADR-079）。
 *
 * <p>レジストリは全モジュールが共有する基盤で、業務の概念を持たない。リスナーが再投入の方針を選ぶ語彙は {@code events} に置く。
 */
@NullMarked
package com.example.demo.shared.eventresubmission;

import org.jspecify.annotations.NullMarked;
