package com.example.demo.mail.infrastructure.client;

import com.example.demo.mail.domain.model.MailRecipient;
import com.example.demo.shared.validation.MailAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
 * <p>宛先が 1 つのメールアドレスの形（{@link MailAddress#PATTERN}）でなければ、{@code *} でも許さない。{@code
 * a@x.org,b@example.com} のように、最後の {@code @} の後ろだけが許可に当たる値を通さないためである。
 */
final class AllowedRecipients {

  /** すべての宛先を許す項目。 */
  private static final String ANY = "*";

  /** メールアドレスの形。入力のメールアドレスと同じ式で検査する。 */
  private static final Pattern MAIL_ADDRESS = Pattern.compile(MailAddress.PATTERN);

  /** すべての宛先を許すか。 */
  private final boolean any;

  /** 許すドメイン。小文字で、{@code @} を含む。 */
  private final Set<String> domains;

  /** 許すアドレス。小文字。 */
  private final Set<String> addresses;

  private AllowedRecipients(
      final boolean any, final Set<String> domains, final Set<String> addresses) {
    this.any = any;
    this.domains = Set.copyOf(domains);
    this.addresses = Set.copyOf(addresses);
  }

  /** 値が 1 つのメールアドレスの形なら true を返す。 */
  /* package */ static boolean isMailAddress(final String value) {
    return MAIL_ADDRESS.matcher(value).matches();
  }

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
            .map(AllowedRecipients::checked)
            .map(entry -> entry.toLowerCase(Locale.ROOT))
            .toList();
    if (entries.isEmpty()) {
      throw new IllegalArgumentException("mail-sender.allowed-recipients must not be empty");
    }
    return new AllowedRecipients(
        entries.contains(ANY),
        entries.stream().filter(entry -> entry.startsWith("@")).collect(Collectors.toSet()),
        entries.stream().filter(AllowedRecipients::isMailAddress).collect(Collectors.toSet()));
  }

  /** 項目が {@code *}、{@code @ドメイン}、アドレスのどれかなら返し、どれでもなければ断る。 */
  private static String checked(final String entry) {
    final boolean valid =
        ANY.equals(entry)
            || (entry.startsWith("@") && isMailAddress("x" + entry))
            || isMailAddress(entry);
    if (!valid) {
      throw new IllegalArgumentException(
          "mail-sender.allowed-recipients has an entry that is neither *, @domain nor a mail address");
    }
    return entry;
  }

  /** 宛先が 1 つのメールアドレスの形で、許可の項目のどれかに当たれば true を返す。 */
  /* package */ boolean allows(final MailRecipient recipient) {
    if (!isMailAddress(recipient.value())) {
      return false;
    }
    final String address = recipient.value().toLowerCase(Locale.ROOT);
    return any
        || addresses.contains(address)
        || domains.contains(address.substring(address.lastIndexOf('@')));
  }

  /** 許可の項目はアドレスを含みうるので、値を伏せる。 */
  @Override
  public String toString() {
    return "AllowedRecipients[***]";
  }
}
