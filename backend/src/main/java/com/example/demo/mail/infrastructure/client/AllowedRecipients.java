package com.example.demo.mail.infrastructure.client;

import com.example.demo.mail.domain.model.MailRecipient;
import com.example.demo.shared.validation.MailAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * メールを送ってよい宛先。許可になければ、SES を呼ばずに送らない。
 *
 * <p>設定の値はカンマ区切りで、各項目は次のどれかである。大文字と小文字は区別しない（docs/mail/email-address.md）。
 *
 * <ul>
 *   <li>{@code *}：すべての宛先を許す。本番の値で、ローカルとテストには置かない。
 *   <li>{@code @example.com}：そのドメインのアドレスを許す。サブドメインは許さない。
 *   <li>{@code user@example.com}：そのアドレスだけを許す。
 * </ul>
 *
 * @param entries 小文字にした許可の項目。{@code *}、{@code @ドメイン}、アドレスのどれか
 */
/* package */ record AllowedRecipients(List<String> entries) {

  /** すべての宛先を許す項目。 */
  private static final String ANY = "*";

  /** ドメインだけを許す項目の形。 */
  private static final Pattern DOMAIN_ENTRY =
      Pattern.compile("^@(?:[a-z0-9][a-z0-9\\-]*\\.)+[a-z]{2,}$");

  /** アドレスの項目の形。 */
  private static final Pattern ADDRESS_ENTRY = Pattern.compile(MailAddress.PATTERN);

  /**
   * カンマ区切りの設定値から作る。項目が 1 つもないか、形の誤った項目があれば、起動を失敗させる。
   *
   * <p>空にすると、すべてのメールが {@code Rejected} として記録されるためである。例外のメッセージに値を入れない（アドレスを含みうる）。
   *
   * @param setting {@code mail-sender.allowed-recipients} の値
   */
  /* package */ static AllowedRecipients parse(final String setting) {
    final List<String> entries =
        Arrays.stream(setting.split(","))
            .map(String::strip)
            .filter(entry -> !entry.isEmpty())
            .map(entry -> entry.toLowerCase(Locale.ROOT))
            .toList();
    if (entries.isEmpty()) {
      throw new IllegalArgumentException("mail-sender.allowed-recipients must not be empty");
    }
    for (final String entry : entries) {
      if (!ANY.equals(entry)
          && !DOMAIN_ENTRY.matcher(entry).matches()
          && !ADDRESS_ENTRY.matcher(entry).matches()) {
        throw new IllegalArgumentException(
            "mail-sender.allowed-recipients has an entry that is neither *, @domain nor a mail address");
      }
    }
    return new AllowedRecipients(entries);
  }

  /** 宛先が許可の項目のどれかに当たれば true を返す。 */
  /* package */ boolean allows(final MailRecipient recipient) {
    final String address = recipient.value().toLowerCase(Locale.ROOT);
    final String domain = address.substring(address.lastIndexOf('@'));
    return entries.stream()
        .anyMatch(entry -> ANY.equals(entry) || entry.equals(address) || entry.equals(domain));
  }
}
