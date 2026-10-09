package com.example.demo.payment;

/**
 * 決済記録の一覧の検索条件。
 *
 * @param orderId 決済した注文の ID
 */
public record PaymentSearchCriteria(String orderId) {}
