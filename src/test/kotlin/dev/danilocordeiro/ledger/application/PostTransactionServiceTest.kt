package dev.danilocordeiro.ledger.application

import dev.danilocordeiro.ledger.application.port.input.PostTransactionCommand
import dev.danilocordeiro.ledger.application.port.input.PostTransactionResult
import dev.danilocordeiro.ledger.application.port.output.AccountRepository
import dev.danilocordeiro.ledger.application.port.output.ClockPort
import dev.danilocordeiro.ledger.application.port.output.IdempotencyRecord
import dev.danilocordeiro.ledger.application.port.output.IdempotencyRepository
import dev.danilocordeiro.ledger.application.port.output.LedgerRepository
import dev.danilocordeiro.ledger.application.port.output.OutboxRepository
import dev.danilocordeiro.ledger.application.port.output.UnitOfWork
import dev.danilocordeiro.ledger.application.service.PostTransactionService
import dev.danilocordeiro.ledger.domain.Account
import dev.danilocordeiro.ledger.domain.AccountBalance
import dev.danilocordeiro.ledger.domain.AccountId
import dev.danilocordeiro.ledger.domain.CurrencyCode
import dev.danilocordeiro.ledger.domain.Direction
import dev.danilocordeiro.ledger.domain.DomainError
import dev.danilocordeiro.ledger.domain.Money
import dev.danilocordeiro.ledger.domain.NormalSide
import dev.danilocordeiro.ledger.domain.TransactionId
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Pure unit tests: no Spring context, no database. This is only possible because the
 * transaction boundary and the clock are ports rather than framework annotations.
 */
class PostTransactionServiceTest {

    private val accounts = mockk<AccountRepository>()
    private val ledger = mockk<LedgerRepository>(relaxed = true)
    private val idempotency = mockk<IdempotencyRepository>(relaxed = true)
    private val outbox = mockk<OutboxRepository>(relaxed = true)
    private val now = Instant.parse("2026-01-15T10:00:00Z")

    private val service = PostTransactionService(
        accounts = accounts,
        ledger = ledger,
        idempotency = idempotency,
        outbox = outbox,
        unitOfWork = object : UnitOfWork {
            override fun <T> inTransaction(block: () -> T): T = block()
        },
        clock = ClockPort { now },
    )

    private val source = AccountId(UUID.randomUUID())
    private val target = AccountId(UUID.randomUUID())
    private val brl = CurrencyCode("BRL")

    @Test
    fun `posts a balanced transfer and emits exactly one outbox event`() {
        givenAccounts(sourceAllowsNegative = false)
        every { idempotency.find(any()) } returns null
        every { ledger.lockAndLoadBalances(any()) } returns mapOf(
            source to AccountBalance(source, signedMinorUnits = 50_000, version = 3),
            target to AccountBalance(target, signedMinorUnits = 0, version = 0),
        )

        val result = service.post(transferCommand(amount = 10_000))

        assertIs<PostTransactionResult.Posted>(result)
        assertEquals(now, result.postedAt)
        verify(exactly = 1) { outbox.append(any()) }
        verify(exactly = 1) { ledger.append(any()) }
        verify(exactly = 1) { idempotency.save(any()) }
    }

    @Test
    fun `replays an identical request without touching the ledger`() {
        val original = TransactionId.new()
        val command = transferCommand(amount = 10_000)
        // Fingerprint is computed by the service; capture it from a first run.
        givenAccounts(sourceAllowsNegative = true)
        every { idempotency.find(any()) } returns null
        every { ledger.lockAndLoadBalances(any()) } returns emptyMap()
        val firstFingerprint = slotFingerprint(command)

        every { idempotency.find(command.idempotencyKey) } returns IdempotencyRecord(
            key = command.idempotencyKey,
            requestFingerprint = firstFingerprint,
            transactionId = original,
            createdAt = now.minusSeconds(60),
        )

        val result = service.post(command)

        val replayed = assertIs<PostTransactionResult.Replayed>(result)
        assertEquals(original, replayed.transactionId)
        verify(exactly = 0) { ledger.append(any()) }
        verify(exactly = 0) { outbox.append(any()) }
    }

    @Test
    fun `rejects a reused key carrying a different payload`() {
        every { idempotency.find("key-reuse") } returns IdempotencyRecord(
            key = "key-reuse",
            requestFingerprint = "a-fingerprint-from-a-different-payload",
            transactionId = TransactionId.new(),
            createdAt = now,
        )

        assertThrows<DomainError.IdempotencyConflict> {
            service.post(transferCommand(amount = 10_000, key = "key-reuse"))
        }
        verify(exactly = 0) { ledger.append(any()) }
    }

    @Test
    fun `rejects a debit that would overdraw an account that forbids negative balances`() {
        givenAccounts(sourceAllowsNegative = false)
        every { idempotency.find(any()) } returns null
        every { ledger.lockAndLoadBalances(any()) } returns mapOf(
            source to AccountBalance(source, signedMinorUnits = 5_000, version = 1),
            target to AccountBalance(target, signedMinorUnits = 0, version = 0),
        )

        val error = assertThrows<DomainError.InsufficientFunds> {
            service.post(transferCommand(amount = 10_000))
        }
        assertEquals(5_000L, error.availableMinorUnits)
        verify(exactly = 0) { ledger.append(any()) }
    }

    @Test
    fun `rejects a transaction whose currency differs from the accounts`() {
        givenAccounts(sourceAllowsNegative = true, currency = CurrencyCode("USD"))
        every { idempotency.find(any()) } returns null

        assertThrows<DomainError.CurrencyMismatch> { service.post(transferCommand(amount = 1_000)) }
    }

    private fun givenAccounts(sourceAllowsNegative: Boolean, currency: CurrencyCode = brl) {
        every { accounts.findAll(any()) } returns mapOf(
            source to Account(source, currency, NormalSide.DEBIT, sourceAllowsNegative),
            target to Account(target, currency, NormalSide.DEBIT, allowsNegative = true),
        )
    }

    /** Runs the command once against a permissive setup to learn its fingerprint. */
    private fun slotFingerprint(command: PostTransactionCommand): String {
        val captured = mutableListOf<IdempotencyRecord>()
        every { idempotency.save(capture(captured)) } returns Unit
        service.post(command.copy(idempotencyKey = command.idempotencyKey + "-probe"))
        return captured.single().requestFingerprint
    }

    private fun transferCommand(amount: Long, key: String = "idem-key-0001") =
        PostTransactionCommand(
            idempotencyKey = key,
            currency = brl,
            entries = listOf(
                PostTransactionCommand.EntryCommand(source, Direction.CREDIT, Money.positiveMinorUnits(amount)),
                PostTransactionCommand.EntryCommand(target, Direction.DEBIT, Money.positiveMinorUnits(amount)),
            ),
            externalReference = "order-42",
        )
}
