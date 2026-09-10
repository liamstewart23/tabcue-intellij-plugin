package ca.liamstewart.tabcue.util

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException

/**
 * Runs [block], logging and swallowing ordinary failures but never cancellation.
 *
 * `runCatching` would also eat `ProcessCanceledException`, which the platform forbids: it breaks
 * cancellation and trips assertions in development builds.
 *
 * `Throwable` rather than `Exception` is necessary, since the reflective facades exist to survive
 * `NoSuchMethodError` and `NoClassDefFoundError` and those are `Error`s. [VirtualMachineError] is
 * still re-thrown, because continuing with a half-styled tab would turn a diagnosable crash into
 * mysterious IDE behaviour.
 */
internal inline fun <T> guarded(log: Logger, message: String, block: () -> T): T? =
    try {
        block()
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: VirtualMachineError) {
        throw e
    } catch (e: Throwable) {
        log.warn(message, e)
        null
    }

/** As [guarded], but for lookups where a failure is expected and not worth a log entry. */
internal inline fun <T> quietly(block: () -> T): T? =
    try {
        block()
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: VirtualMachineError) {
        throw e
    } catch (e: Throwable) {
        null
    }
