/**
 * API のエラー応答を検証するテスト専用の HTTP API。
 *
 * <p>com.example.demo の外に置き、component scan、ArchUnit、exportOpenApi の書き出しに含めない。 ApiErrorContractTest
 * だけが {@link ErrorFixture} で読み込む。
 */
@NullMarked
package errorfixture.presentation.web;

import org.jspecify.annotations.NullMarked;
