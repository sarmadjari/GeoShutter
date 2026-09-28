package com.saschl.cameragps.utils

import android.content.Context
import com.sasch.cameragps.sharednew.crash.CrashReportPolicy
import com.saschl.cameragps.BuildConfig
import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryLogLevel
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import io.sentry.android.timber.SentryTimberIntegration

/**
 * gplay crash reporting: Sentry. Only ever initialized after the user consented
 * (see SentryConsentDialog / CameraGpsApplication), and only offered when the
 * build was given a valid DSN (`sentry.dsn` in local.properties or as a Gradle
 * property, or SENTRY_DSN). The foss flavor ships a no-op counterpart of this object.
 */
object CrashReporting {

    private val dsn: String = BuildConfig.SENTRY_DSN.trim()

    /** Gates the consent dialog and the Sentry settings entry. */
    val AVAILABLE: Boolean = CrashReportPolicy.isValidDsn(dsn)

    fun init(context: Context) {
        if (!AVAILABLE) return
        SentryAndroid.init(context) { options ->
            options.dsn = dsn
            options.isSendDefaultPii = false

            options.logs.isEnabled = true

            // These thresholds are the ones CrashReportPolicy.route encodes for
            // both platforms — change them together.
            options.addIntegration(
                SentryTimberIntegration(
                    minEventLevel = SentryLevel.ERROR,
                    minBreadcrumbLevel = SentryLevel.INFO,
                    minLogsLevel = SentryLogLevel.INFO
                )
            )
            // Scrubbing lives in the shared module so iOS redacts identically.
            options.logs.beforeSend = SentryOptions.Logs.BeforeSendLogCallback { event ->
                event.body = CrashReportPolicy.redact(event.body)
                event
            }
            options.beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
                SentryRedaction.redact(event)
            }
            options.beforeBreadcrumb = SentryOptions.BeforeBreadcrumbCallback { breadcrumb, _ ->
                SentryRedaction.redact(breadcrumb)
            }
        }
    }
}

/**
 * Removes Bluetooth MAC addresses from everything Sentry would send besides
 * structured logs: event messages, exception messages and breadcrumbs.
 */
internal object SentryRedaction {

    fun redact(event: SentryEvent): SentryEvent {
        event.message?.let { message ->
            message.formatted = message.formatted?.let(CrashReportPolicy::redact)
            message.message = message.message?.let(CrashReportPolicy::redact)
            message.params = message.params?.map(CrashReportPolicy::redact)
        }
        event.exceptions?.forEach { exception ->
            exception.value = exception.value?.let(CrashReportPolicy::redact)
        }
        event.breadcrumbs?.forEach(::redact)
        return event
    }

    fun redact(breadcrumb: Breadcrumb): Breadcrumb {
        breadcrumb.message = breadcrumb.message?.let(CrashReportPolicy::redact)
        return breadcrumb
    }
}
