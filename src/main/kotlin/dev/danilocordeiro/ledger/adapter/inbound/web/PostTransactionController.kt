package dev.danilocordeiro.ledger.adapter.inbound.web

import dev.danilocordeiro.ledger.application.port.input.PostTransactionCommand
import dev.danilocordeiro.ledger.application.port.input.PostTransactionResult
import dev.danilocordeiro.ledger.application.port.input.PostTransactionUseCase
import dev.danilocordeiro.ledger.domain.AccountId
import dev.danilocordeiro.ledger.domain.CurrencyCode
import dev.danilocordeiro.ledger.domain.Direction
import dev.danilocordeiro.ledger.domain.Money
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/v1/transactions")
class PostTransactionController(private val useCase: PostTransactionUseCase) {

    /**
     * The idempotency key is a header, not a body field: it describes the delivery of
     * the request rather than the transaction itself, and keeping it out of the body
     * means it is excluded from the payload fingerprint by construction.
     */
    @PostMapping
    fun post(
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: PostTransactionRequest,
    ): ResponseEntity<PostTransactionResponse> {
        val result = useCase.post(request.toCommand(idempotencyKey))

        // 201 for a fresh posting, 200 for a replay. Same body either way, so a client
        // that ignores the status still behaves correctly.
        val status = when (result) {
            is PostTransactionResult.Posted -> HttpStatus.CREATED
            is PostTransactionResult.Replayed -> HttpStatus.OK
        }
        val postedAt = when (result) {
            is PostTransactionResult.Posted -> result.postedAt
            is PostTransactionResult.Replayed -> result.originallyPostedAt
        }
        return ResponseEntity.status(status).body(
            PostTransactionResponse(
                transactionId = result.transactionId.value,
                postedAt = postedAt,
                replayed = result is PostTransactionResult.Replayed,
            ),
        )
    }
}

data class PostTransactionRequest(
    @field:Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO-4217 code")
    val currency: String,

    @field:NotEmpty
    @field:Size(min = 2, max = 100, message = "a transaction must have between 2 and 100 entries")
    @field:Valid
    val entries: List<EntryRequest>,

    @field:Size(max = 256)
    val externalReference: String? = null,
) {
    fun toCommand(idempotencyKey: String) = PostTransactionCommand(
        idempotencyKey = idempotencyKey,
        currency = CurrencyCode(currency),
        entries = entries.map {
            PostTransactionCommand.EntryCommand(
                accountId = AccountId.of(it.accountId),
                direction = Direction.valueOf(it.direction),
                amount = Money.positiveMinorUnits(it.amountMinorUnits),
            )
        },
        externalReference = externalReference,
    )

    data class EntryRequest(
        val accountId: String,
        @field:Pattern(regexp = "^(DEBIT|CREDIT)$")
        val direction: String,
        @field:Positive
        val amountMinorUnits: Long,
    )
}

data class PostTransactionResponse(
    val transactionId: UUID,
    val postedAt: Instant,
    val replayed: Boolean,
)
