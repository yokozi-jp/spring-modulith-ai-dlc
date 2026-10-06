package com.example.demo.order.application;

import com.example.demo.shared.concurrency.ExpectedLockNo;
import com.example.demo.shared.concurrency.VersionedCommand;

/**
 * 下書きの注文を取り消すユースケースの入力。
 *
 * @param orderId 注文 ID
 * @param expectedLockNo 画面が読んだ注文のロック番号
 */
public record CancelOrderCommand(String orderId, ExpectedLockNo expectedLockNo)
    implements VersionedCommand {}
