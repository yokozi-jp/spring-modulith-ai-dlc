package com.example.demo.mail.infrastructure.client;

import com.example.demo.mail.domain.model.MailBody;
import com.example.demo.mail.domain.model.MailMessageId;
import com.example.demo.mail.domain.model.MailOutcome;
import com.example.demo.mail.domain.model.MailRecipient;
import com.example.demo.mail.domain.model.MailSender;
import com.example.demo.mail.domain.model.MailSubject;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.BadRequestException;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

/**
 * SES v2 の {@code SendEmail} で、1 人の宛先へプレーンテキストのメールを送る。
 *
 * <p>本番もローカルもテストも、この Client で送る。ローカルとテストは、{@link SesV2ClientConfig} が Spring の設定の接続先と資格情報で WireMock
 * に向ける（ADR-125）。タイムアウトと試行 1 回の設定は {@link SesV2ClientConfig} が持ち、circuit breaker と retry は ADR-019
 * の既定値のままにする。SES の SLO から値を変える根拠がまだないためである。
 *
 * <p>許可の外の宛先（{@link AllowedRecipients}）、{@code MessageRejected}、{@code BadRequestException}、{@code
 * MessageId} のない応答は拒否の結果にし、ほかの失敗は例外を投げる。分け方と理由は docs/mail/sending.md の「結果と失敗の扱い」 に示す。
 *
 * <p>Client のログと、投げる SES の例外のメッセージには、例外の型、SES のエラーコード、要求の ID だけを入れ、宛先のアドレスと SES
 * のエラーの文面を入れない（ADR-015）。SES の文面は、確かめられなかった宛先や ID を含みうるためである。
 */
@Slf4j
@Component
class MailSenderClient implements MailSender {

  /** 件名と本文の文字コード。 */
  private static final String CHARSET = StandardCharsets.UTF_8.name();

  /** From の表示名を包む RFC 2047 の encoded-word の前半。 */
  private static final String ENCODED_WORD_PREFIX = "=?" + CHARSET + "?B?";

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

  /** 送ってよい宛先。 */
  private final AllowedRecipients allowedRecipients;

  /**
   * From と設定セットを持つ Client を作る。値に既定値はなく、未設定なら起動に失敗する（ADR-008）。
   *
   * <p>形式の誤った From のアドレスと、空白だけの表示名と設定セットも起動の時点で断る。SES は要求ごとに {@code BadRequestException}
   * を返し、すべてのメールが {@code Rejected} として記録されてしまうためである。
   *
   * @param sesV2Client タイムアウトを持ち、再試行しない SES v2 の Client
   * @param fromAddress From のアドレス。SES で検証した ID のアドレスにする
   * @param fromName From の表示名。UTF-8 で 45 バイト以下にする
   * @param configurationSet 送るメールに付ける SES の設定セットの名前
   * @param allowedRecipients 送ってよい宛先。カンマ区切りで、{@code *}、{@code @ドメイン}、アドレスのどれか
   */
  /* package */ MailSenderClient(
      final SesV2Client sesV2Client,
      @Value("${mail-sender.from-address}") final String fromAddress,
      @Value("${mail-sender.from-name}") final String fromName,
      @Value("${mail-sender.configuration-set}") final String configurationSet,
      @Value("${mail-sender.allowed-recipients}") final String allowedRecipients) {
    if (!AllowedRecipients.isMailAddress(fromAddress)) {
      throw new IllegalArgumentException("mail-sender.from-address is not a mail address");
    }
    if (fromName.isBlank()) {
      throw new IllegalArgumentException("mail-sender.from-name must not be blank");
    }
    if (configurationSet.isBlank()) {
      throw new IllegalArgumentException("mail-sender.configuration-set must not be blank");
    }
    this.sesV2Client = sesV2Client;
    this.from = encodedWord(fromName) + " <" + fromAddress + ">";
    this.configurationSet = configurationSet;
    this.allowedRecipients = AllowedRecipients.parse(allowedRecipients);
  }

  /**
   * メールを送り、業務の状態に記録する結果を返す。
   *
   * <p>許可の外の宛先には SES を呼ばず、拒否の結果にする。同じ要求で回復しない拒否と、契約に合わない応答も拒否の結果にする。それ以外の失敗は、SES の文面を落とした同じ型の SDK
   * の例外で投げる。
   */
  @CircuitBreaker(name = "mail-sender")
  @Retry(name = "mail-sender")
  @Override
  public MailOutcome send(final MailRecipient to, final MailSubject subject, final MailBody body) {
    if (!allowedRecipients.allows(to)) {
      log.warn("The mail was not sent: the recipient is not in mail-sender.allowed-recipients");
      return MailOutcome.rejected();
    }
    final String messageId;
    try {
      messageId = sesV2Client.sendEmail(request(to, subject, body)).messageId();
    } catch (MessageRejectedException | BadRequestException exception) {
      return rejectedBySes(exception);
    } catch (SesV2Exception exception) {
      throw withoutErrorText(exception);
    }
    if (messageId == null || messageId.isBlank()) {
      log.warn("SES reply violated the contract: MessageId is missing");
      return MailOutcome.rejected();
    }
    return MailOutcome.sent(new MailMessageId(messageId));
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

  /** SES が拒否したことを、例外の型、エラーコード、要求の ID だけで記録し、拒否の結果を返す。 */
  private static MailOutcome rejectedBySes(final SesV2Exception exception) {
    final String type = exception.getClass().getSimpleName();
    final @Nullable String errorCode = errorCode(exception);
    final @Nullable String requestId = exception.requestId();
    log.warn(
        "SES rejected the mail: exception={}, errorCode={}, requestId={}",
        type,
        errorCode,
        requestId);
    return MailOutcome.rejected();
  }

  /**
   * SES のエラーの文面を除いた、同じ型の例外を作る。型、状態のコード、エラーコード、要求の ID は残す。
   *
   * <p>投げた例外のメッセージは、Spring Modulith と非同期の実行のログに出る。SES の文面は宛先と ID を含みうるため、ここで落とす。
   */
  private static AwsServiceException withoutErrorText(final SesV2Exception exception) {
    final @Nullable AwsErrorDetails details = exception.awsErrorDetails();
    final @Nullable String errorCode = errorCode(exception);
    return exception.toBuilder()
        .message("SES returned an error: errorCode=" + errorCode)
        .awsErrorDetails(
            details == null
                ? null
                : AwsErrorDetails.builder()
                    .errorCode(errorCode)
                    .serviceName(details.serviceName())
                    .build())
        .cause(null)
        .build();
  }

  /** SES のエラーコード。詳細がなければ null。 */
  private static @Nullable String errorCode(final SesV2Exception exception) {
    final @Nullable AwsErrorDetails details = exception.awsErrorDetails();
    return details == null ? null : details.errorCode();
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
