package com.example.demo.payment.application;

/**
 * 確定した注文の代金を請求するユースケースの入力。
 *
 * @param orderId 請求する注文の ID
 */
public record ChargeOrderCommand(String orderId) {}
