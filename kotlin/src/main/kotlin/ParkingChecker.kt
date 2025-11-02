package com.parking

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

@Serializable
data class EmailConfig(
    val smtpServer: String = "smtp.gmail.com",
    val smtpPort: Int = 587,
    val fromEmail: String = "",
    val fromPassword: String = "",
    val toEmail: String = ""
)

@Serializable
data class Config(
    val url: String = "https://monthly.mkp.jp/parking/004884-00/",
    val email: EmailConfig = EmailConfig(),
    val checkIntervalMinutes: Int = 60
)

@Serializable
data class ParkingState(
    val hasVacancy: Boolean = false,
    val details: String = "",
    val timestamp: String = ""
)

class ParkingChecker(private val configFile: String = "config.json") {
    private val config: Config
    private val stateFile = "state.json"
    private val url: String
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }
    }

    init {
        config = loadConfig()
        url = config.url
    }

    private fun loadConfig(): Config {
        val file = File(configFile)
        return if (file.exists()) {
            Json.decodeFromString(File(configFile).readText())
        } else {
            val defaultConfig = Config()
            saveConfig(defaultConfig)
            println("⚠️  設定ファイルが見つかりませんでした。デフォルト設定を作成しました: $configFile")
            println("⚠️  config.jsonを編集してメール設定を行ってください。")
            defaultConfig
        }
    }

    private fun saveConfig(config: Config) {
        File(configFile).writeText(Json.encodeToString(Config.serializer(), config))
    }

    private fun loadState(): ParkingState? {
        val file = File(stateFile)
        return if (file.exists()) {
            try {
                Json.decodeFromString<ParkingState>(file.readText())
            } catch (e: Exception) {
                println("❌ 状態ファイルの読み込みに失敗しました: ${e.message}")
                null
            }
        } else {
            null
        }
    }

    private fun saveState(state: ParkingState) {
        try {
            File(stateFile).writeText(Json.encodeToString(ParkingState.serializer(), state))
        } catch (e: Exception) {
            println("❌ 状態ファイルの保存に失敗しました: ${e.message}")
        }
    }

    private suspend fun fetchPage(): String? {
        return try {
            val response = client.get(url) {
                headers {
                    append("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                }
            }
            response.body<String>()
        } catch (e: Exception) {
            println("❌ ページの取得に失敗しました: ${e.message}")
            null
        }
    }

    private fun parseAvailability(html: String): ParkingState {
        val soup = Jsoup.parse(html)
        
        // 具体的なHTML構造に基づいて空き状況を判定
        // body#parking > div#page > div#contents > div.con_parkingdetail > div.title > span.ic_situation
        val situationElement = soup.select("body#parking div#page div#contents div.con_parkingdetail div.title span.ic_situation").firstOrNull()
        
        val hasVacancy: Boolean
        val details: String
        
        if (situationElement != null) {
            val className = situationElement.className()
            
            // classNameで判定: "ic_situation full", "ic_situation contact", "ic_situation empty"
            hasVacancy = when {
                className.contains("empty") -> true   // 空きあり
                className.contains("contact") -> true  // 問い合わせ（空きありとして通知）
                className.contains("full") -> false    // 満車
                else -> {
                    println("⚠️  予期しないclassNameが見つかりました: $className")
                    false
                }
            }
            
            // 詳細情報（タイトル部分のテキスト）
            val titleElement = soup.select("body#parking div#page div#contents div.con_parkingdetail div.title").firstOrNull()
            details = titleElement?.text()?.trim() ?: "空き状況の詳細を取得できませんでした"
            
            println("ℹ️  空き状況を検出: className=$className, hasVacancy=$hasVacancy")
        } else {
            // 要素が見つからない場合のフォールバック
            println("⚠️  空き状況の要素が見つかりませんでした。HTMLを保存します。")
            File("last_page.html").writeText(html)
            println("ℹ️  ページのHTMLを last_page.html に保存しました。確認してください。")
            
            // フォールバック: テキスト検索（最後の手段）
            val text = soup.text().lowercase()
            val vacancyKeywords = listOf("空き", "空", "empty")
            val fullKeywords = listOf("満車", "満", "full", "contact")
            
            hasVacancy = when {
                vacancyKeywords.any { text.contains(it) } && !fullKeywords.any { text.contains(it) } -> true
                fullKeywords.any { text.contains(it) } -> false
                else -> {
                    println("⚠️  フォールバック判定でも空き状況を判定できませんでした")
                    false // デフォルトは満車として扱う
                }
            }
            details = "HTML構造が変更された可能性があります。手動で確認してください。"
        }

        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

        return ParkingState(
            hasVacancy = hasVacancy,
            details = details,
            timestamp = timestamp
        )
    }

    private fun sendEmail(subject: String, body: String): Boolean {
        val emailConfig = config.email

        if (emailConfig.fromEmail.isEmpty() || 
            emailConfig.fromPassword.isEmpty() || 
            emailConfig.toEmail.isEmpty()) {
            println("❌ メール設定が不完全です。config.jsonを確認してください。")
            return false
        }

        return try {
            val props = Properties().apply {
                put("mail.smtp.host", emailConfig.smtpServer)
                put("mail.smtp.port", emailConfig.smtpPort.toString())
                put("mail.smtp.auth", "true")
                put("mail.smtp.starttls.enable", "true")
            }

            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(emailConfig.fromEmail, emailConfig.fromPassword)
                }
            })

            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(emailConfig.fromEmail))
                setRecipient(Message.RecipientType.TO, InternetAddress(emailConfig.toEmail))
                this.subject = subject
                setText(body, "UTF-8")
            }

            Transport.send(message)
            println("✅ メールを送信しました: ${emailConfig.toEmail}")
            true
        } catch (e: Exception) {
            println("❌ メール送信に失敗しました: ${e.message}")
            false
        }
    }

    suspend fun check() {
        println("🔍 パーキングサイトをチェック中: $url")

        val html = fetchPage()
        if (html == null) {
            println("❌ ページの取得に失敗しました")
            return
        }

        val currentState = parseAvailability(html)
        val previousState = loadState()

        saveState(currentState)

        if (currentState.hasVacancy) {
            println("✅ 空きが見つかりました！")

            // 前回も空きがあった場合は通知しない
            if (previousState?.hasVacancy == true) {
                println("ℹ️  前回も空きがあったため、通知をスキップします")
            } else {
                // メール通知を送信
                val subject = "【パーキング空き情報】空きが出ました！"
                val body = """
パーキングに空きが出ました！

URL: $url
チェック時刻: ${currentState.timestamp}

詳細:
${currentState.details}

すぐに確認してください！
""".trimIndent()

                sendEmail(subject, body)
            }
        } else {
            println("ℹ️  現在満車です")
        }
    }
}

fun main() = runBlocking {
    val checker = ParkingChecker()
    checker.check()
}

