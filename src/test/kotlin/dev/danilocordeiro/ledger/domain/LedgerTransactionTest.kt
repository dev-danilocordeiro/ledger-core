package dev.danilocordeiro.ledger.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class LedgerTransactionTest {

    private val cash = AccountId(UUID.randomUUID())
    private val wallet = AccountId(UUID.randomUUID())
    private val brl = CurrencyCode("BRL")

    @Test
    fun `rejects a transaction whose debits and credits differ`() {
        val error = assertThrows<DomainError.UnbalancedTransaction> {
            transaction(
                Entry(cash, Direction.DEBIT, Money.positiveMinorUnits(10_000)),
                Entry(wallet, Direction.CREDIT, Money.positiveMinorUnits(9_999)),
            )
        }
        assertEquals("unbalanced transaction: debits and credits differ by 1 minor units", error.message)
    }

    @Test
    fun `rejects a single-sided transaction`() {
        assertThrows<DomainError.UnbalancedTransaction> {
            transaction(Entry(cash, Direction.DEBIT, Money.positiveMinorUnits(10_000)))
        }
    }

    @Test
    fun `accepts a multi-leg transaction that balances across more than two accounts`() {
        val fees = AccountId(UUID.randomUUID())
        val posted = transaction(
            Entry(cash, Direction.DEBIT, Money.positiveMinorUnits(10_000)),
            Entry(wallet, Direction.CREDIT, Money.positiveMinorUnits(9_500)),
            Entry(fees, Direction.CREDIT, Money.positiveMinorUnits(500)),
        )
        assertEquals(3, posted.entries.size)
        assertEquals(0L, posted.entries.sumOf { it.signedMinorUnits })
    }

    @Test
    fun `nets repeated entries on the same account`() {
        val posted = transaction(
            Entry(cash, Direction.DEBIT, Money.positiveMinorUnits(10_000)),
            Entry(cash, Direction.CREDIT, Money.positiveMinorUnits(2_000)),
            Entry(wallet, Direction.CREDIT, Money.positiveMinorUnits(8_000)),
        )
        assertEquals(8_000L, posted.netByAccount().getValue(cash))
        assertEquals(-8_000L, posted.netByAccount().getValue(wallet))
    }

    @Test
    fun `rejects a non-positive entry amount at construction`() {
        assertThrows<IllegalArgumentException> { Money.positiveMinorUnits(0) }
        assertThrows<IllegalArgumentException> { Money.positiveMinorUnits(-1) }
    }

    @Test
    fun `credit-normal account reports a positive available balance for a credit balance`() {
        val balance = AccountBalance(wallet, signedMinorUnits = -8_000, version = 1)
        assertEquals(8_000L, balance.available(NormalSide.CREDIT).minorUnits)
        assertEquals(-8_000L, balance.available(NormalSide.DEBIT).minorUnits)
    }

    private fun transaction(vararg entries: Entry) = LedgerTransaction.post(
        id = TransactionId.new(),
        currency = brl,
        entries = entries.toList(),
        occurredAt = Instant.parse("2026-01-15T10:00:00Z"),
    )
}
