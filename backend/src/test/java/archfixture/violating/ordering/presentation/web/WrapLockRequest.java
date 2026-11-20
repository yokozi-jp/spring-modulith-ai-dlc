package archfixture.violating.ordering.presentation.web;

/**
 * 違反ではない Request。controllersDoNotCreateRequests の違反フィクスチャの Controller が作る。
 *
 * @param lockNo ロック番号
 */
public record WrapLockRequest(long lockNo) {}
