package com.example.demo.order.application;

/**
 * 下書きの注文を確定するユースケースの結果。
 *
 * @param orderId 確定した注文の ID
 */
public record ConfirmOrderResult(String orderId) {}
