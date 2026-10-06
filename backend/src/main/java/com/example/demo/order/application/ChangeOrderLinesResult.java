package com.example.demo.order.application;

/**
 * 下書きの注文の明細を置き換えるユースケースの結果。
 *
 * @param orderId 明細を置き換えた注文の ID
 */
public record ChangeOrderLinesResult(String orderId) {}
