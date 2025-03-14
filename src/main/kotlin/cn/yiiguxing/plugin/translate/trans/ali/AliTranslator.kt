package cn.yiiguxing.plugin.translate.trans.ali

import cn.yiiguxing.plugin.translate.ALI_TRANSLATE_PRODUCT_URL
import cn.yiiguxing.plugin.translate.ALI_TRANSLATE_URL
import cn.yiiguxing.plugin.translate.message
import cn.yiiguxing.plugin.translate.trans.*
import cn.yiiguxing.plugin.translate.ui.settings.TranslationEngine.ALI
import cn.yiiguxing.plugin.translate.util.Settings
import cn.yiiguxing.plugin.translate.util.hmacSha1
import cn.yiiguxing.plugin.translate.util.i
import cn.yiiguxing.plugin.translate.util.md5Base64
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.HttpRequests
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.Icon


// https://www.aliyun.com/product/ai/base_alimt
object AliTranslator : AbstractTranslator(), DocumentationTranslator {

    private val SUPPORTED_LANGUAGES: List<Lang> = listOf(
        Lang.CHINESE,
        Lang.CHINESE_TRADITIONAL,
        Lang.ENGLISH,
        Lang.JAPANESE,
        Lang.KOREAN,
        Lang.FRENCH,
        Lang.SPANISH,
        Lang.ITALIAN,
        Lang.GERMAN,
        Lang.TURKISH,
        Lang.RUSSIAN,
        Lang.PORTUGUESE,
        Lang.VIETNAMESE,
        Lang.INDONESIAN,
        Lang.THAI,
        Lang.MALAY,
        Lang.ARABIC,
        Lang.HINDI
    )

    private val SUPPORTED_TARGET_LANGUAGES: List<Lang> = listOf(
        Lang.CHINESE,
        Lang.ENGLISH,
        Lang.JAPANESE,
        Lang.KOREAN,
        Lang.FRENCH,
        Lang.SPANISH,
        Lang.ITALIAN,
        Lang.GERMAN,
        Lang.TURKISH,
        Lang.RUSSIAN,
        Lang.PORTUGUESE,
        Lang.VIETNAMESE,
        Lang.INDONESIAN,
        Lang.THAI,
        Lang.MALAY
    )

    private val logger: Logger = Logger.getInstance(AliTranslator::class.java)

    override val id: String = ALI.id

    override val name: String = ALI.translatorName

    override val icon: Icon = ALI.icon

    override val intervalLimit: Int = ALI.intervalLimit

    override val contentLengthLimit: Int = ALI.contentLengthLimit

    override val primaryLanguage: Lang
        get() = ALI.primaryLanguage

    override val supportedSourceLanguages: List<Lang> = SUPPORTED_LANGUAGES
        .toMutableList()
        .apply { add(0, Lang.AUTO) }
    override val supportedTargetLanguages: List<Lang> = SUPPORTED_TARGET_LANGUAGES

    override fun checkConfiguration(force: Boolean): Boolean {
        if (force || Settings.aliTranslateSettings.let { it.appId.isEmpty() || it.getAppKey().isEmpty() }) {
            return ALI.showConfigurationDialog()
        }

        return true
    }

    override fun doTranslate(text: String, srcLang: Lang, targetLang: Lang): Translation {
        return SimpleTranslateClient(
            this,
            { _, _, _ -> call(text, srcLang, targetLang, false) },
            AliTranslator::parseTranslation
        ).execute(text, srcLang, targetLang)
    }

    override fun translateDocumentation(documentation: String, srcLang: Lang, targetLang: Lang): BaseTranslation {
        return checkError {
            val client = SimpleTranslateClient(
                this,
                { _, _, _ -> call(documentation, srcLang, targetLang, true) },
                AliTranslator::parseTranslation
            )
            with(client) {
                updateCacheKey { it.update("DOCUMENTATION".toByteArray()) }
                execute(documentation, srcLang, targetLang)
            }
        }
    }

    /**
     * 序列化json模型
     */
    @Suppress("MemberVisibilityCanBePrivate")
    data class AliTranslationRequest constructor(
        @SerializedName("SourceText")
        val sourceText: String,
        @SerializedName("SourceLanguage")
        val sourceLanguage: String,
        @SerializedName("TargetLanguage")
        val targetLanguage: String,
        @SerializedName("FormatType")
        val formatType: String = "text",
        @SerializedName("Scene")
        val scene: String = "general"
    )

    private fun call(text: String, srcLang: Lang, targetLang: Lang, isDocumentation: Boolean): String {
        val formatType = if (isDocumentation) "html" else "text"
        val request = AliTranslationRequest(text, srcLang.aliLanguageCode, targetLang.aliLanguageCode, formatType)

        return sendHttpRequest(ALI_TRANSLATE_URL, request)
    }

    private fun sendHttpRequest(url: String, request: Any): String {
        val body = Gson().toJson(request)

        val realUrl = URL(url)
        val accept = "application/json"
        val contentType = "application/json"
        val date: String = toGMTString(Date())
        val bodyMd5: String = body.md5Base64()
        val uuid = UUID.randomUUID().toString()
        val stringToSign = """
            POST
            $accept
            $bodyMd5
            $contentType
            $date
            x-acs-signature-method:HMAC-SHA1
            x-acs-signature-nonce:$uuid
            x-acs-version:2019-01-02
            ${realUrl.file}
            """.trimIndent()

        val settings = Settings.aliTranslateSettings

        return HttpRequests
            .post(url, contentType)
            .tuner {
                it.setRequestProperty("Accept", accept)
                it.setRequestProperty("Content-MD5", bodyMd5)
                it.setRequestProperty("Date", date)
                it.setRequestProperty("Host", realUrl.host)
                it.setRequestProperty(
                    "Authorization",
                    "acs ${settings.appId}:${stringToSign.hmacSha1(settings.getAppKey())}"
                )
                it.setRequestProperty("x-acs-signature-nonce", uuid)
                it.setRequestProperty("x-acs-signature-method", "HMAC-SHA1")
                it.setRequestProperty("x-acs-version", "2019-01-02") // 版本可选
            }
            .connect {
                it.write(body)
                it.readString()
            }
    }

    private fun parseTranslation(
        translation: String,
        original: String,
        srcLang: Lang,
        targetLang: Lang
    ): Translation {
        logger.i("Translate result: $translation")
        return Gson().fromJson(translation, AliTranslation::class.java).apply {
            query = original
            src = srcLang
            target = targetLang
            if (!isSuccessful) {
                throw TranslateResultException(code, name)
            }
        }.toTranslation()
    }

    override fun createErrorMessage(throwable: Throwable): String = when (throwable) {
        is TranslateResultException -> when (throwable.code) {
            10001 -> message("error.request.timeout")
            10002 -> message("error.systemError")
            10003 -> message("error.bad.request")
            10004 -> message("error.missingParameter")
            10005 -> message("error.language.unsupported")
            10006 -> message("error.ali.language.detecting.failed")
            10007 -> message("error.systemError")
            10008 -> message("error.text.too.long")
            10009 -> message("error.ali.permission.denied")
            10010 -> message("error.service.is.down", ALI_TRANSLATE_PRODUCT_URL)
            10011 -> message("error.systemError")
            10012 -> message("error.systemError")
            10013 -> message("error.account.has.run.out.of.balance")
            else -> message("error.unknown") + "[${throwable.code}]"
        }
        else -> super.createErrorMessage(throwable)
    }

    private fun toGMTString(date: Date): String {
        val df = SimpleDateFormat("E, dd MMM yyyy HH:mm:ss z", Locale.UK)
        df.timeZone = SimpleTimeZone(0, "GMT")
        return df.format(date)
    }
}
