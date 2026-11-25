package com.example.demo.ordering.application;

import com.example.demo.shared.concurrency.ExpectedLockNo;
import com.example.demo.shared.concurrency.VersionedCommand;
import com.example.demo.shared.idempotency.IdempotencyKey;
import com.example.demo.shared.idempotency.IdempotentCommand;

/**
 * 下書きの注文を確定するユースケースの入力。
 *
 * @param orderId 注文 ID
 * @param expectedLockNo 画面が読んだ注文のロック番号
 * @param idempotencyKey クライアントが確定の操作ごとに採番したキー
 */
public record ConfirmOrderCommand(
    String orderId, ExpectedLockNo expectedLockNo, IdempotencyKey idempotencyKey)
    implements VersionedCommand, IdempotentCommand {}
