package com.example.demo.order;

import org.jspecify.annotations.Nullable;

/**
 * 注文の一覧の検索条件。
 *
 * @param status 絞り込む注文の状態。null ならすべての状態
 */
public record OrderSearchCriteria(@Nullable String status) {}
