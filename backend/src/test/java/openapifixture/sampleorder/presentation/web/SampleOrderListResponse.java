package openapifixture.sampleorder.presentation.web;

import java.util.List;

/**
 * サンプル注文の一覧。
 *
 * @param items サンプル注文の詳細の一覧
 */
record SampleOrderListResponse(List<SampleOrderResponse> items) {}
