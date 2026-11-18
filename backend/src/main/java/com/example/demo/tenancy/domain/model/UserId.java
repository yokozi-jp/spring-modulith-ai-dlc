package com.example.demo.tenancy.domain.model;

import java.util.UUID;

/**
 * 利用者 ID（{@code public_id}）。共通カラムの作成者と更新者に登録する値でもある。
 *
 * @param value 利用者 ID の UUID
 */
public record UserId(UUID value) {}
