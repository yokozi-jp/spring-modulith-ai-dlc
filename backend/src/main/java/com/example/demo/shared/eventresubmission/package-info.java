/**
 * Spring Modulith のイベント出版のレジストリの、失敗した出版の定期の再投入と手での 1 回の再投入（ADR-079）、完了した出版の archive
 * の行を保持期限で消す定期の実行（ADR-120）を置く。
 *
 * <p>レジストリは全モジュールが共有する基盤で、業務の概念を持たない。リスナーが再投入の方針を選ぶ語彙は {@code events} に置く。
 * 他のモジュールの本番のコードは使わない。{@code EventPublicationResubmitOnce} だけは、他のモジュールの結合テストがリスナーの回復を確かめるため public
 * にする。
 */
@NullMarked
package com.example.demo.shared.eventresubmission;

import org.jspecify.annotations.NullMarked;
