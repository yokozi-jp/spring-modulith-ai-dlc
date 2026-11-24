package com.example.demo.testkit;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import org.yaml.snakeyaml.Yaml;

/**
 * テストの JVM ごとに 1 つずつ起動する PostgreSQL、決済代行と SES v2 の API の WireMock のコンテナ（ADR-085）。
 *
 * <p>Gradle は fork ごとに別の JVM を起動するため、fork どうしは DB を共有しない。 最初の Spring のテストのコンテキストを作るときに起動し、JVM の終了で
 * Testcontainers の Ryuk が片付ける。 イメージと WireMock のコマンドは {@code docker/compose-test.yml} から読み、E2E
 * とスモークの依存と版をそろえる。 ポートは compose と同じく {@code 127.0.0.1} だけで待ち受ける。
 */
final class InfrastructureContainers {

  /** compose のテスト用スタック。イメージとコマンドの正本。作業ディレクトリは {@code backend}。 */
  private static final Path COMPOSE_FILE = Path.of("../docker/compose-test.yml");

  /** compose と同じ初期化のスクリプト（マイグレーションロール、liquibase スキーマ、アプリロール）。 */
  private static final Path INITDB_DIR = Path.of("../docker/initdb");

  /** 決済代行のスタブ。compose の WireMock と同じものを読む。 */
  private static final Path WIREMOCK_MAPPINGS = Path.of("../docker/wiremock/mappings");

  /** changelog のクラスパス上の位置。 */
  private static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";

  /** Liquibase の管理表を置くスキーマ。{@code backend/gradle/database.gradle} と同じ。 */
  private static final String LIQUIBASE_SCHEMA = "liquibase";

  /** 適用するスキーマタグ。Gradle の test が {@code gradle.properties} の版を渡す。空なら全 changeset を適用する。 */
  private static final String TAG_PROPERTY = "test.liquibase.tag";

  /** コンテナのポートを公開するホストのアドレス。compose と同じく loopback に固定し、LAN に公開しない。 */
  @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
  private static final String LOOPBACK = "127.0.0.1";

  /** 使い捨てのコンテナの DB 名。機密ではない。 */
  private static final String DB_NAME = "demo";

  /** DML だけを持つアプリロール。 */
  private static final String APP_USER = "demo";

  /** DDL とスキーマを持つマイグレーションロール。initdb が superuser でも BYPASSRLS でもないロールとして作る。 */
  private static final String MIGRATION_USER = "demo_migration";

  /** コンテナの初期化だけに使う superuser。テストもマイグレーションもこのロールで接続しない。 */
  private static final String SUPERUSER = "postgres";

  /** 使い捨てのコンテナのパスワード。JVM ごとに作り、固定の値をコードに置かない。 */
  private static final String PASSWORD = UUID.randomUUID().toString();

  /** compose の PostgreSQL のサービス名。Testcontainers の公式イメージの名前と同じ。 */
  private static final String POSTGRES = "postgres";

  /** compose の WireMock のサービス名。 */
  private static final String WIREMOCK = "wiremock";

  /** compose の service の項目のうち、イメージ。 */
  private static final String IMAGE_KEY = "image";

  /** PostgreSQL の待ち受けポート。 */
  private static final int POSTGRES_PORT = 5432;

  /** WireMock の待ち受けポート。 */
  private static final int WIREMOCK_PORT = 8080;

  /** 決済代行のスタブが請求に返す status。 */
  private static final int CHARGED = 201;

  /** SES v2 の API のスタブが SendEmail に返す status。 */
  private static final int OK = 200;

  /** テストの SDK が使うリージョン。WireMock はリージョンを見ない。 */
  private static final String AWS_REGION = "ap-northeast-1";

  /** テストの SDK のダミーのアクセスキー。WireMock は署名を見ない。 */
  private static final String AWS_ACCESS_KEY_ID = "test-dummy-access-key";

  /** テストの SDK のダミーのシークレットキー。WireMock は署名を見ない。 */
  private static final String AWS_SECRET_ACCESS_KEY = "test-dummy-secret-key";

  private InfrastructureContainers() {}

  /** 起動した依存の接続先を、{@code application.yaml} のプレースホルダの名前で返す。初回の呼び出しで起動する。 */
  /* package */ static Map<String, Object> properties() {
    return Holder.PROPERTIES;
  }

  /** 初回の参照で一度だけ起動する。 */
  private static final class Holder {

    /** 接続先。 */
    private static final Map<String, Object> PROPERTIES = start();
  }

  // コンテナは JVM の終了まで使い、Ryuk が片付けるため、try-with-resources で閉じない。
  @SuppressWarnings({"resource", "PMD.CloseResource"})
  private static Map<String, Object> start() {
    final Map<String, Object> compose = composeServices();
    final PostgreSQLContainer postgres =
        new PostgreSQLContainer(
                DockerImageName.parse(image(compose, POSTGRES))
                    // ダイジェスト付きの名前は既定の照合に通らないため、postgres の公式イメージであることを示す。
                    .asCompatibleSubstituteFor(POSTGRES))
            .withDatabaseName(DB_NAME)
            .withUsername(SUPERUSER)
            .withPassword(PASSWORD)
            .withEnv("MIGRATION_DB_USERNAME", MIGRATION_USER)
            .withEnv("MIGRATION_DB_PASSWORD", PASSWORD)
            .withEnv("DB_USERNAME", APP_USER)
            .withEnv("DB_PASSWORD", PASSWORD)
            .withCopyFileToContainer(
                MountableFile.forHostPath(INITDB_DIR), "/docker-entrypoint-initdb.d/");
    final GenericContainer<?> wireMock =
        new GenericContainer<>(DockerImageName.parse(image(compose, WIREMOCK)))
            .withCommand(command(compose, WIREMOCK))
            .withCopyFileToContainer(
                MountableFile.forHostPath(WIREMOCK_MAPPINGS), "/home/wiremock/mappings/")
            .withExposedPorts(WIREMOCK_PORT)
            .waitingFor(Wait.forHttp("/__admin/health"));
    bindToLoopback(postgres, POSTGRES_PORT);
    bindToLoopback(wireMock, WIREMOCK_PORT);
    Startables.deepStart(postgres, wireMock).join();

    final String wireMockUrl = "http://" + LOOPBACK + ":" + wireMock.getMappedPort(WIREMOCK_PORT);
    warmUp(wireMockUrl);
    final String dbPort = String.valueOf(postgres.getMappedPort(POSTGRES_PORT));
    migrate("jdbc:postgresql://" + LOOPBACK + ":" + dbPort + "/" + DB_NAME);
    return Map.ofEntries(
        Map.entry("DB_HOST", LOOPBACK),
        Map.entry("DB_PORT", dbPort),
        Map.entry("DB_NAME", DB_NAME),
        Map.entry("DB_USERNAME", APP_USER),
        Map.entry("DB_PASSWORD", PASSWORD),
        Map.entry("MIGRATION_DB_USERNAME", MIGRATION_USER),
        Map.entry("MIGRATION_DB_PASSWORD", PASSWORD),
        Map.entry("PAYMENT_GATEWAY_BASE_URL", wireMockUrl),
        Map.entry("AWS_ENDPOINT_URL_SESV2", wireMockUrl),
        Map.entry("AWS_REGION", AWS_REGION),
        Map.entry("AWS_ACCESS_KEY_ID", AWS_ACCESS_KEY_ID),
        Map.entry("AWS_SECRET_ACCESS_KEY", AWS_SECRET_ACCESS_KEY));
  }

  /** コンテナのポートを、空いているホストのポートへ、{@code 127.0.0.1} だけで公開する。 */
  private static void bindToLoopback(final GenericContainer<?> container, final int port) {
    container
        .withExposedPorts(port)
        .withCreateContainerCmdModifier(cmd ->
            cmd.getHostConfig()
                .withPortBindings(
                    new PortBinding(Ports.Binding.bindIp(LOOPBACK), ExposedPort.tcp(port))));
  }

  /**
   * 決済代行と SES v2 の API のスタブを 1 回ずつ呼ぶ。
   *
   * <p>ponytail: WireMock の最初の請求は JsonPath とテンプレートのクラスの読み込みで遅く、負荷の下では {@code
   * payment-gateway.read-timeout} と {@code mail-sender.read-timeout} を超えるため、テストより先に 1 回ずつ呼ぶ。要求の形は
   * {@code payment-gateway-charge.json} と {@code ses-send-email.json} の照合の写しなので、スタブの照合を変えたらここも変える。
   * スタブが増えたら、各スタブを温めるか、テストの read-timeout を延ばす。
   */
  // 割り込まれたら、割り込みの状態を戻して失敗させる。
  @SuppressWarnings("PMD.DoNotUseThreads")
  private static void warmUp(final String baseUrl) {
    final HttpRequest charge =
        HttpRequest.newBuilder(URI.create(baseUrl + "/v1/charges"))
            .header("Idempotency-Key", "warm-up")
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"orderId\":\"warm-up\",\"amount\":\"1\",\"currency\":\"JPY\"}"))
            .build();
    final HttpRequest sendEmail =
        HttpRequest.newBuilder(URI.create(baseUrl + "/v2/email/outbound-emails"))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"FromEmailAddress\":\"warm-up@example.com\","
                        + "\"Destination\":{\"ToAddresses\":[\"warm-up@example.com\"]},"
                        + "\"Content\":{\"Simple\":{\"Subject\":{\"Data\":\"warm-up\"},"
                        + "\"Body\":{\"Text\":{\"Data\":\"warm-up\"}}}},"
                        + "\"ConfigurationSetName\":\"warm-up\"}"))
            .build();
    try (HttpClient client = HttpClient.newHttpClient()) {
      for (final Map.Entry<HttpRequest, Integer> call :
          List.of(Map.entry(charge, CHARGED), Map.entry(sendEmail, OK))) {
        final String path = call.getKey().uri().getPath();
        final int status =
            client.send(call.getKey(), HttpResponse.BodyHandlers.discarding()).statusCode();
        if (status != call.getValue()) {
          throw new IllegalStateException("スタブの準備の呼び出しが失敗した: path=" + path + ", status=" + status);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("スタブを準備できない", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("スタブの準備の途中で割り込まれた", e);
    }
  }

  /** マイグレーションロールで changeset を適用する。本番と同じく、アプリロールへの GRANT を changeset が付ける。 */
  // database と接続は Liquibase#close が閉じる。二重に閉じると、閉じた接続の autocommit を戻そうとして失敗する。
  @SuppressWarnings("PMD.CloseResource")
  private static void migrate(final String jdbcUrl) {
    final String tag = System.getProperty(TAG_PROPERTY, "");
    try {
      final Database database =
          DatabaseFactory.getInstance()
              .findCorrectDatabaseImplementation(
                  new JdbcConnection(
                      DriverManager.getConnection(jdbcUrl, MIGRATION_USER, PASSWORD)));
      migrate(database, tag);
    } catch (SQLException | LiquibaseException e) {
      throw new IllegalStateException("テスト用の DB へ changeset を適用できない: tag=" + tag, e);
    }
  }

  private static void migrate(final Database database, final String tag) throws LiquibaseException {
    try (Liquibase liquibase =
        new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
      database.setDefaultSchemaName(LIQUIBASE_SCHEMA);
      database.setLiquibaseSchemaName(LIQUIBASE_SCHEMA);
      liquibase.setChangeLogParameter("appDatabaseUsername", APP_USER);
      if (tag.isBlank()) {
        liquibase.update(new Contexts());
      } else {
        liquibase.update(tag, new Contexts());
      }
    }
  }

  private static Map<String, Object> composeServices() {
    try (Reader reader = Files.newBufferedReader(COMPOSE_FILE)) {
      final Map<String, Object> root = new Yaml().load(reader);
      return asMap(root.get("services"), "services");
    } catch (IOException e) {
      throw new UncheckedIOException("compose のファイルを読めない: " + COMPOSE_FILE, e);
    }
  }

  private static String image(final Map<String, Object> services, final String service) {
    if (asMap(services.get(service), service).get(IMAGE_KEY) instanceof String image) {
      return image;
    }
    throw new IllegalStateException(COMPOSE_FILE + " の " + service + " に image がない");
  }

  /** compose の service のコマンド。配列で書いたものだけを読み、文字列で書いたものは誤りにする。 */
  private static String[] command(final Map<String, Object> services, final String service) {
    if (asMap(services.get(service), service).get("command") instanceof List<?> list) {
      return list.stream().map(String::valueOf).toArray(String[]::new);
    }
    throw new IllegalStateException(COMPOSE_FILE + " の " + service + " の command が配列でない");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(final Object value, final String key) {
    if (value instanceof Map<?, ?>) {
      return (Map<String, Object>) value;
    }
    throw new IllegalStateException(COMPOSE_FILE + " に " + key + " がない");
  }
}
