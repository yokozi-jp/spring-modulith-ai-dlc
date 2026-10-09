package com.example.demo.ordering.application;

/**
 * 下書きの注文を取り消すユースケースの結果。
 *
 * @param orderId 取り消した注文の ID
 */
public record CancelOrderResult(String orderId) {}
