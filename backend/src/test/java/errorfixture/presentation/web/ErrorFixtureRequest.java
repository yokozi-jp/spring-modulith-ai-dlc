package errorfixture.presentation.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * エラー応答の検証用の要求。
 *
 * @param name 必須の名前
 * @param items 明細
 */
record ErrorFixtureRequest(
    @NotBlank(message = "{validation.required}") String name,
    @Valid List<ErrorFixtureItem> items) {}
