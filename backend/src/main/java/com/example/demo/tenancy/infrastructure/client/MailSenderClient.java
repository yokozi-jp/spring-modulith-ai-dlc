package com.example.demo.tenancy.infrastructure.client;

import com.example.demo.tenancy.domain.model.MailBody;
import com.example.demo.tenancy.domain.model.MailMessageId;
import com.example.demo.tenancy.domain.model.MailOutcome;
import com.example.demo.tenancy.domain.model.MailRecipient;
import com.example.demo.tenancy.domain.model.MailSender;
import com.example.demo.tenancy.domain.model.MailSubject;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.BadRequestException;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

/**
 * SES v2 の {@code SendEmail} で、1 人の宛先へプレーンテキストのメールを送る。
 *
 * <p>本番もローカルもテストも、この Client で送る。ローカルとテストは、SDK の接続先の {@code AWS_ENDPOINT_URL_SESV2} だけを WireMock
 * に向ける（ADR-125）。タイムアウトと試行 1 回の設定は {@link SesV2ClientConfig} が持ち、circuit breaker と retry は ADR-019
 * の既定値のままにする。SES の SLO から値を変える根拠がまだないためである。
 *
 * <p>結果は ADR-072 の分け方に合わせる。{@code MessageRejected} と {@code BadRequestException}
 * は、同じ要求を送り直しても受け付けられないので、例外を投げずに拒否の結果を返し、呼び出し元が業務の状態に記録する。{@code MessageId} のない応答も、送り直すと 2
 * 通目が出うるので拒否の結果にする。接続の失敗、タイムアウト、スロットリング、5xx、資格情報と権限の不備、SES の設定の不備は例外を投げ、イベント出版を FAILED
 * に残して再投入を待つ。circuit breaker はスロットリング以外の 4xx を失敗に数えない（application.yaml の {@code
 * ignore-exception-predicate}）。
 *
 * <p>ログには例外の型と SES のエラーコードだけを出し、宛先のアドレスと SES のエラーの文面を出さない（ADR-015）。{@code MessageRejected}
 * の文面は、確かめられなかった宛先を含みうるためである。
 */
@Slf4j
@Component
class MailSenderClient implements MailSender {

  /** 件名と本文の文字コード。 */
  private static final String CHARSET = "UTF-8";

  /** From の表示名を包む RFC 2047 の encoded-word の前半。 */
  private static final String ENCODED_WORD_PREFIX = "=?UTF-8?B?";

  /** From の表示名を包む RFC 2047 の encoded-word の後半。 */
  private static final String ENCODED_WORD_SUFFIX = "?=";

  /** RFC 2047 の 1 つの encoded-word の長さの上限。 */
  private static final int MAX_ENCODED_WORD_LENGTH = 75;

  /** メールを送る SES v2 の Client。 */
  private final SesV2Client sesV2Client;

  /** 表示名を RFC 2047 で符号化した、From のヘッダーの値。 */
  private final String from;

  /** 送るメールに付ける SES の設定セットの名前。 */
  private final String configurationSet;

  /**
   * From と設定セットを持つ Client を作る。値に既定値はなく、未設定なら起動に失敗する（ADR-008）。
   *
   * @param sesV2Client タイムアウトを持ち、再試行しない SES v2 の Client
   * @param fromAddress From のアドレス。SES で検証した ID のアドレスにする
   * @param fromName From の表示名。UTF-8 で 45 バイト以下にする
   * @param configurationSet 送るメールに付ける SES の設定セットの名前
   */
  /* package */ MailSenderClient(
      final SesV2Client sesV2Client,
      @Value("${mail-sender.from-address}") final String fromAddress,
      @Value("${mail-sender.from-name}") final String fromName,
      @Value("${mail-sender.configuration-set}") final String configurationSet) {
    this.sesV2Client = sesV2Client;
    this.from = encodedWord(fromName) + " <" + fromAddress + ">";
    this.configurationSet = configurationSet;
  }

  /**
   * メールを送り、業務の状態に記録する結果を返す。
   *
   * <p>同じ要求で回復しない拒否と、契約に合わない応答は拒否の結果にする。それ以外の失敗は SDK の例外のまま投げる。
   */
  @CircuitBreaker(name = "mail-sender")
  @Retry(name = "mail-sender")
  @Override
  public MailOutcome send(final MailRecipient to, final MailSubject subject, final MailBody body) {
    final String messageId;
    try {
      messageId = sesV2Client.sendEmail(request(to, subject, body)).messageId();
    } catch (MessageRejectedException | BadRequestException exception) {
      return rejected(exception);
    }
    if (messageId == null || messageId.isBlank()) {
      log.warn("SES reply violated the contract: MessageId is missing");
      return new MailOutcome.Rejected();
    }
    return new MailOutcome.Sent(new MailMessageId(messageId));
  }

  /** 宛先を TO の 1 人にし、件名と本文を UTF-8 のプレーンテキストにした要求を作る。 */
  private SendEmailRequest request(
      final MailRecipient to, final MailSubject subject, final MailBody body) {
    return SendEmailRequest.builder()
        .fromEmailAddress(from)
        .destination(destination -> destination.toAddresses(to.value()))
        .content(content ->
            content.simple(message ->
                message.subject(text(subject.value())).body(part -> part.text(text(body.value())))))
        .configurationSetName(configurationSet)
        .build();
  }

  /** SES が拒否したことを、例外の型とエラーコードだけで記録し、拒否の結果を返す。 */
  private static MailOutcome rejected(final SesV2Exception exception) {
    final String type = exception.getClass().getSimpleName();
    final @Nullable AwsErrorDetails details = exception.awsErrorDetails();
    final @Nullable String errorCode = details == null ? null : details.errorCode();
    log.warn("SES rejected the mail: exception={}, errorCode={}", type, errorCode);
    return new MailOutcome.Rejected();
  }

  /** UTF-8 の文字列の Content を作る。 */
  private static Content text(final String data) {
    return Content.builder().data(data).charset(CHARSET).build();
  }

  /**
   * 表示名を RFC 2047 の encoded-word（UTF-8 の Base64）にする。SES は ASCII 以外の表示名をこの形で受ける。
   *
   * <p>ponytail: 表示名を 1 つの encoded-word にするため、UTF-8 で 45 バイト（日本語で 15
   * 文字）を超えると起動に失敗する。長い表示名が要るときは、文字の境目で複数の encoded-word に分ける。
   */
  private static String encodedWord(final String text) {
    final String word =
        ENCODED_WORD_PREFIX
            + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8))
            + ENCODED_WORD_SUFFIX;
    if (word.length() > MAX_ENCODED_WORD_LENGTH) {
      throw new IllegalArgumentException(
          "mail-sender.from-name is too long for one encoded-word: length=" + word.length());
    }
    return word;
  }
}
