package com.obsidianscout.utils

import ch.qos.logback.classic.AsyncAppender
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import org.slf4j.LoggerFactory

/**
 * Moves the root logger's console appender behind an [AsyncAppender], so writing a log line never blocks a request
 * thread on stdout (slow on Windows consoles and when output is piped to a service manager).
 *
 * Done in code rather than in logback.xml: logback is initialized at native-image build time, and an AsyncAppender
 * declared there would start its worker thread during the build, which native-image rejects.
 */
object AsyncConsoleLogging {
    fun install() {
        val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
        val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
        val console = root.getAppender("STDOUT") ?: return
        val async = AsyncAppender().apply {
            this.context = context
            name = "ASYNC_STDOUT"
            queueSize = 8192
            discardingThreshold = 0 // keep INFO and DEBUG lines too, not only WARN and ERROR
            isNeverBlock = true // a full queue drops a line rather than stalling a request
            addAppender(console)
            start()
        }
        root.addAppender(async)
        root.detachAppender(console)

        // Flush what is queued at exit, and send whatever other shutdown hooks log straight to the console.
        Runtime.getRuntime().addShutdownHook(Thread {
            root.addAppender(console)
            root.detachAppender(async)
            async.stop()
        })
    }
}
