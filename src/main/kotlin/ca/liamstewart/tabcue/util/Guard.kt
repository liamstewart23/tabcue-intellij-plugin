package ca.liamstewart.tabcue.util

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException

/**
 * Runs [block], logging and swallowing ordinary failures but never cancellation.
 *
 * Plain `runCatching` catches `Throwable`, which means it also eats `ProcessCanceledException`.
 * The platform forbids that: swallowing a PCE breaks cancellation propagation and trips assertions
 * in development builds. Everything in this plugin is best-effort decoration, so ordinary
 * exceptions are logged and ignored — but cancellation must be allowed through.
 *
 * `Throwable` rather than `Exception` is deliberate and necessary: the reflective terminal facade
 * exists precisely to survive `NoSuchMethodError` and `NoClassDefFoundError` when the experimental
 * API moves, and those are `Error`s. [VirtualMachineError] is re-thrown, though — an
 * `OutOfMemoryError` or `StackOverflowError` says the JVM is in trouble, and quietly continuing
 * with a half-styled tab would turn a diagnosable crash into mysterious IDE behaviour.
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
