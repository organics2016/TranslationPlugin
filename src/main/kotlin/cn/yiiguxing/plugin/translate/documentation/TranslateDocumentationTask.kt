package cn.yiiguxing.plugin.translate.documentation

import cn.yiiguxing.plugin.translate.trans.Translator
import cn.yiiguxing.plugin.translate.util.TranslateService
import cn.yiiguxing.plugin.translate.util.invokeLater
import cn.yiiguxing.plugin.translate.util.w
import com.intellij.lang.Language
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import org.jetbrains.concurrency.runAsync
import java.util.concurrent.TimeoutException

class TranslateDocumentationTask(
    val text: String,
    val language: Language? = null,
    val translator: Translator = TranslateService.translator
) {

    private val totalTimeToWaitMs = 3_000
    private val timeToBlockMs = 100

    private var tries = totalTimeToWaitMs / timeToBlockMs

    //execute on a different thread outside read action
    private val promise = runAsync {
        translator.getTranslatedDocumentation(text, language)
    }.onError { LOG.w(it) }

    fun onSuccess(callback: (String) -> Unit) {
        promise.onSuccess(callback)
    }

    fun nonBlockingGet(): String? {
        //blocking for the whole time can lead to ui freezes, so we need to periodically do `checkCanceled`
        while (tries > 0) {
            tries -= 1
            ProgressManager.checkCanceled()
            try {
                return promise.blockingGet(timeToBlockMs)
            } catch (t: TimeoutException) {
                //ignore
            } catch (e: Throwable) {
                invokeLater { DocNotifications.showWarning(e, null) }
                return null
            }
        }

        //translation is not ready yet, show original documentation
        return null
    }

    companion object {
        private val LOG = Logger.getInstance(TranslateDocumentationTask::class.java)
    }
}
