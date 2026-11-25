/**
 * API のエラー応答を検証するテスト専用の HTTP API。
 *
 * <p>com.example.demo の外に置き、component scan、ArchUnit、OpenAPI の契約の書き出しに含めない。 ApiErrorContractTest だけが
 * {@link ErrorFixture} で読み込む。業務規則の違反のテストが渡す理由の {@link ErrorFixtureFailure} も置く。
 */
@NullMarked
package errorfixture.presentation.web;

import org.jspecify.annotations.NullMarked;
