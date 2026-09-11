package migration

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

/**
 * DATABASECHANGELOG を直接照会し、選択されたスキーマタグが存在しなければ失敗する。
 *
 * Liquibase の tagExists コマンドはタグが無くても終了コード 0 を返すため、
 * リリースゲートでは終了コードに依存せず、このタスクで存在を保証する。
 *
 * JDBC ドライバは通常ビルドの実行時クラスパスへ載せず、jooqCodegen 構成から
 * URLClassLoader 経由で読み込む。ドライバ classpath だけを入力として追跡し、
 * 接続情報（URL・資格情報・スキーマ・タグ）は @Internal としてビルドキャッシュへ
 * 取り込まない。出力を宣言しないため、このタスクは毎回実行される。
 */
abstract class AssertSchemaTagExistsTask extends DefaultTask {

    /** PostgreSQL JDBC ドライバを含む classpath（通常は jooqCodegen 構成）。 */
    @Classpath
    abstract ConfigurableFileCollection getDriverClasspath()

    /** マイグレーション対象 DB の JDBC URL。 */
    @Internal
    abstract Property<String> getJdbcUrl()

    /** マイグレーション用アカウントのユーザー名。 */
    @Internal
    abstract Property<String> getUsername()

    /** マイグレーション用アカウントのパスワード。 */
    @Internal
    abstract Property<String> getPassword()

    /** DATABASECHANGELOG を照会するスキーマ名。 */
    @Internal
    abstract Property<String> getSchema()

    /** 存在を確認する Liquibase スキーマタグ。 */
    @Internal
    abstract Property<String> getTag()

    @TaskAction
    void verify() {
        def schemaName = schema.get()
        def tagValue = tag.get()

        def driverUrls = driverClasspath.files.collect { driverFile ->
            driverFile.toURI().toURL()
        } as URL[]
        def driverClassLoader = new URLClassLoader(driverUrls, ClassLoader.getPlatformClassLoader())
        def connection
        def statement
        def resultSet
        try {
            def driver = driverClassLoader
                .loadClass('org.postgresql.Driver')
                .getDeclaredConstructor()
                .newInstance()
            def connectionProperties = new Properties()
            connectionProperties.setProperty('user', username.get())
            connectionProperties.setProperty('password', password.get())
            connection = driver.connect(jdbcUrl.get(), connectionProperties)
            if (connection == null) {
                throw new GradleException('PostgreSQL JDBCドライバがMIGRATION_DB_URLを処理できません。')
            }
            connection.setReadOnly(true)
            connection.setSchema(schemaName)
            statement = connection.prepareStatement(
                'SELECT EXISTS (SELECT 1 FROM databasechangelog WHERE tag = ?)'
            )
            statement.setString(1, tagValue)
            resultSet = statement.executeQuery()
            if (!resultSet.next() || !resultSet.getBoolean(1)) {
                throw new GradleException(
                    "スキーマ${schemaName}のDATABASECHANGELOGに" +
                    "Liquibaseタグ${tagValue}が存在しません。"
                )
            }
        } finally {
            resultSet?.close()
            statement?.close()
            connection?.close()
            driverClassLoader.close()
        }
    }
}
