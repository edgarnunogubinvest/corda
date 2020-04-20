package net.corda.common.logging.errorReporting

import java.util.*

/**
 * Entry point into the Error Reporting framework.
 *
 * This creates the error reporter used to report errors. The `initialiseReporting` method should be called to build a reporter before any
 * errors are reported.
 */
class ErrorReporting private constructor(private val localeString: String,
                                         private val resourceLocation: String,
                                         private val contextProvider: ErrorContextProvider?) {

    constructor() : this(DEFAULT_LOCALE, DEFAULT_LOCATION, null)

    private companion object {
        private const val DEFAULT_LOCALE = "en-US"
        private const val DEFAULT_LOCATION = "."

        private var errorReporter: ErrorReporter? = null
    }

    /**
     * Set the locale to use when reporting errors
     *
     * @param locale The locale tag to use when reporting errors, e.g. en-US
     */
    fun withLocale(locale: String) : ErrorReporting {
        return ErrorReporting(locale, resourceLocation, contextProvider)
    }

    /**
     * Set the location of the resource bundles containing the error codes.
     *
     * @param location The location within the JAR of the resource bundle
     */
    fun usingResourcesAt(location: String) : ErrorReporting {
        return ErrorReporting(localeString, location, contextProvider)
    }

    fun withContextProvider(contextProvider: ErrorContextProvider) : ErrorReporting {
        return ErrorReporting(localeString, resourceLocation, contextProvider)
    }

    /**
     * Set up the reporting of errors.
     */
    fun initialiseReporting() {
        if (contextProvider == null) {
            throw NoContextProviderSuppliedException()
        }
        if (errorReporter != null) {
            throw DoubleInitializationException()
        }
        errorReporter = ErrorReporterImpl(resourceLocation, Locale.forLanguageTag(localeString), contextProvider)
    }

    internal fun getReporter() : ErrorReporter {
        return errorReporter ?: throw ReportingUninitializedException()
    }
}