package dev.danilocordeiro.ledger.adapter.inbound.web

import dev.danilocordeiro.ledger.domain.DomainError
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/**
 * RFC 9457 problem details. A generic 500 for a rejected posting is unactionable for an
 * integrating client; the error type URI lets them branch on the cause programmatically
 * instead of parsing a message string.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(DomainError::class)
    fun handleDomainError(error: DomainError): ProblemDetail = when (error) {
        is DomainError.UnbalancedTransaction ->
            problem(HttpStatus.UNPROCESSABLE_ENTITY, "unbalanced-transaction", error)

        is DomainError.CurrencyMismatch ->
            problem(HttpStatus.UNPROCESSABLE_ENTITY, "currency-mismatch", error)

        is DomainError.AccountNotFound ->
            problem(HttpStatus.NOT_FOUND, "account-not-found", error).also {
                it.setProperty("accountId", error.accountId.value)
            }

        is DomainError.InsufficientFunds ->
            problem(HttpStatus.UNPROCESSABLE_ENTITY, "insufficient-funds", error).also {
                it.setProperty("accountId", error.accountId.value)
                it.setProperty("availableMinorUnits", error.availableMinorUnits)
            }

        is DomainError.IdempotencyConflict ->
            problem(HttpStatus.CONFLICT, "idempotency-conflict", error)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ProblemDetail {
        val detail = e.bindingResult.fieldErrors.joinToString("; ") {
            "${it.field}: ${it.defaultMessage}"
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail).apply {
            type = URI.create("https://ledger.danilocordeiro.dev/errors/validation")
            title = "Invalid request"
        }
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.message ?: "invalid argument").apply {
            type = URI.create("https://ledger.danilocordeiro.dev/errors/validation")
            title = "Invalid request"
        }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ProblemDetail {
        // Log the cause, return nothing internal: stack traces in responses leak schema
        // and library versions to anyone probing the API.
        log.error("unhandled exception", e)
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "An unexpected error occurred.",
        ).apply {
            type = URI.create("https://ledger.danilocordeiro.dev/errors/internal")
            title = "Internal error"
        }
    }

    private fun problem(status: HttpStatus, slug: String, error: DomainError) =
        ProblemDetail.forStatusAndDetail(status, error.message ?: slug).apply {
            type = URI.create("https://ledger.danilocordeiro.dev/errors/$slug")
            title = slug.replace('-', ' ').replaceFirstChar { it.uppercase() }
        }
}
