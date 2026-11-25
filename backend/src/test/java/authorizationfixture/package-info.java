/**
 * 認可の表の判定が違反を検出することを確かめる、テスト専用の HTTP API。
 *
 * <p>com.example.demo の外に置き、component scan、ArchUnit、OpenAPI の契約の書き出しに含めない。
 * AuthorizationJudgmentTest だけが、Spring の context を起動しない MockMvc で呼ぶ。
 */
@NullMarked
package authorizationfixture;

import org.jspecify.annotations.NullMarked;
