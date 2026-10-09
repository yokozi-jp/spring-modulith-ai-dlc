package com.example.demo.ordering.application;

/**
 * 下書きの注文を作るユースケースの結果。
 *
 * @param orderId 作った注文の ID
 */
public record DraftOrderResult(String orderId) {}
