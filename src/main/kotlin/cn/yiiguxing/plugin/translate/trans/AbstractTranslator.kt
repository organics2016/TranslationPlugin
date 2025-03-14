package cn.yiiguxing.plugin.translate.trans

import cn.yiiguxing.plugin.translate.message
import com.google.gson.JsonSyntaxException
import com.intellij.util.io.HttpRequests
import io.netty.handler.codec.http.HttpResponseStatus
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * Provides a skeletal implementation of the [Translator] interface.
 */
abstract class AbstractTranslator : Translator {

    final override fun translate(text: String, srcLang: Lang, targetLang: Lang): Translation = checkError {
        doTranslate(text, srcLang, targetLang)
    }

    protected abstract fun doTranslate(text: String, srcLang: Lang, targetLang: Lang): Translation

    protected inline fun <T> checkError(action: () -> T): T = try {
        action()
    } catch (throwable: Throwable) {
        onError(throwable)
    }

    protected open fun createErrorMessage(throwable: Throwable): String = when (throwable) {
        is UnsupportedLanguageException -> message("error.unsupportedLanguage", throwable.lang.langName)
        is SocketException, is SSLHandshakeException -> message("error.network")
        is ConnectException, is UnknownHostException -> message("error.network.connection")
        is SocketTimeoutException -> message("error.network.timeout")
        is JsonSyntaxException -> message("error.parse")
        is HttpRequests.HttpStatusException -> HttpResponseStatus.valueOf(throwable.statusCode).reasonPhrase()
        else -> when (
            throwable.message?.let { HTTP_STATUS_EXCEPTION_REGEX.matchEntire(it) }?.let { it.groupValues[1].toInt() }
        ) {
            429 -> message("error.too.many.requests")
            else -> message("error.unknown")
        }
    }

    protected open fun onError(throwable: Throwable): Nothing {
        val errorMessage = message("error.translate.failed", createErrorMessage(NetworkException.unwrap(throwable)))
        throw TranslateException(errorMessage, name, throwable)
    }

    companion object {
        private val HTTP_STATUS_EXCEPTION_REGEX = Regex("^Server returned HTTP response code: (\\d{3}) .+$")
    }

}