/**
 * ロックを取って定期に動くジョブの、時間の規則とロックの構成を置く（ADR-079、ADR-126）。
 *
 * <p>ロックの実体は ShedLock である。利用側は ShedLock の型（{@code LockingTaskExecutor}、{@code
 * LockConfiguration}、{@code LockException}）をそのまま使い、ここでは包まない。ShedLock 以外の実装が必要になったときに、包むかを決める。
 */
@NullMarked
package com.example.demo.shared.locking;

import org.jspecify.annotations.NullMarked;
