package com.obsidianscout.utils

import ch.qos.logback.classic.AsyncAppender
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.Appender
import ch.qos.logback.core.OutputStreamAppender
import ch.qos.logback.core.UnsynchronizedAppenderBase
import org.slf4j.LoggerFactory

/**
 * Moves the root logger's console appender behind an [AsyncAppender], so writing a log line never blocks a request
 * thread on stdout (slow on Windows consoles and when output is piped to a service manager).
 *
 * When [ConsoleLogFile] is on, each log line is also written to the log file, and that happens on the logging
 * thread rather than behind the queue: lines still queued for the console are lost when the process dies, and
 * those are the ones a crash investigation needs. Appending to a file costs microseconds, unlike the console.
 *
 * Done in code rather than in logback.xml: logback is initialized at native-image build time, and an AsyncAppender
 * declared there would start its worker thread during the build, which native-image rejects.
 */
object AsyncConsoleLogging {
    fun install() {
        val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
        val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
        val stdout = root.getAppender("STDOUT") ?: return
        val toFile = ConsoleLogFile.isActive
        // System.out copies what it prints into the log file. Log lines get there through LogFileAppender instead,
        // so their console copy is printed with that switched off, or each would be in the file twice.
        val console: Appender<ILoggingEvent> = if (toFile) {
            ConsoleOnlyAppender(stdout).apply {
                this.context = context
                name = "STDOUT_ONLY"
                start()
            }
        } else {
            stdout
        }
        val async = AsyncAppender().apply {
            this.context = context
            name = "ASYNC_STDOUT"
            queueSize = 8192
            discardingThreshold = 0 // keep INFO and DEBUG lines too, not only WARN and ERROR
            isNeverBlock = true // a full queue drops a line rather than stalling a request
            addAppender(console)
            start()
        }
        if (toFile) {
            root.addAppender(LogFileAppender(stdout).apply {
                this.context = context
                name = "CONSOLE_LOG_FILE"
                start()
            })
        }
        root.addAppender(async)
        root.detachAppender(stdout)

        // Flush what is queued at exit, and send whatever other shutdown hooks log straight to the console.
        Runtime.getRuntime().addShutdownHook(Thread {
            root.addAppender(console)
            root.detachAppender(async)
            async.stop()
        })
    }

    /** Prints through [console] without System.out's copy into the log file. */
    private class ConsoleOnlyAppender(private val console: Appender<ILoggingEvent>) : UnsynchronizedAppenderBase<ILoggingEvent>() {
        override fun append(event: ILoggingEvent) = ConsoleLogFile.consoleOnly { console.doAppend(event) }
    }

    /** Writes each log line to the console log file, formatted the way [console] prints it. */
    private class LogFileAppender(console: Appender<ILoggingEvent>) : UnsynchronizedAppenderBase<ILoggingEvent>() {
        private val encoder = (console as? OutputStreamAppender<ILoggingEvent>)?.encoder

        override fun append(event: ILoggingEvent) {
            ConsoleLogFile.writeLogLine(encoder?.encode(event) ?: plainLine(event))
        }

        private fun plainLine(event: ILoggingEvent): ByteArray {
            val trace = event.throwableProxy?.let { ThrowableProxyUtil.asString(it) + "\n" } ?: ""
            return "${event.level} ${event.loggerName} - ${event.formattedMessage}\n$trace".toByteArray()
        }
    }
}
