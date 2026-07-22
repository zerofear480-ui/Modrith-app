package com.modrith.app.install

import com.modrith.launcher.LauncherLogger
import timber.log.Timber

class TimberLauncherLogger : LauncherLogger {
    override fun debug(event: String, attributes: Map<String, Any?>) =
        Timber.tag(TAG).d(format(event, attributes))

    override fun info(event: String, attributes: Map<String, Any?>) =
        Timber.tag(TAG).i(format(event, attributes))

    override fun warn(
        event: String,
        attributes: Map<String, Any?>,
        cause: Throwable?,
    ) = Timber.tag(TAG).w(cause, format(event, attributes))

    override fun error(
        event: String,
        attributes: Map<String, Any?>,
        cause: Throwable?,
    ) = Timber.tag(TAG).e(cause, format(event, attributes))

    private fun format(
        event: String,
        attributes: Map<String, Any?>,
    ): String = attributes.entries.joinToString(
        prefix = "$event {",
        postfix = "}",
    ) { (key, value) -> "$key=$value" }

    private companion object {
        const val TAG = "LauncherDiscovery"
    }
}
