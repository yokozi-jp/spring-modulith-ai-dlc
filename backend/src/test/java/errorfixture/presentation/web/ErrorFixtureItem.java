package errorfixture.presentation.web;

import jakarta.validation.constraints.Size;

/**
 * エラー応答の検証用の明細。
 *
 * @param code 3 文字以下のコード
 */
record ErrorFixtureItem(@Size(max = 3, message = "{validation.size}") String code) {}
