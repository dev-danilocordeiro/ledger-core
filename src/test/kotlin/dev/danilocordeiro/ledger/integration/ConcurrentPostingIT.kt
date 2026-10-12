package dev.danilocordeiro.ledger.integration

import dev.danilocordeiro.ledger.application.port.input.PostTransactionCommand
import dev.danilocordeiro.ledger.application.port.input.PostTransactionResult
import dev.danilocordeiro.ledger.application.port.input.PostTransactionUseCase
import dev.danilocordeiro.ledger.domain.AccountId
import dev.danilocordeiro.ledger.domain.CurrencyCode
import dev.danilocordeiro.ledger.domain.Direction
import dev.danilocordeiro.ledger.domain.DomainError
import dev.danilocordeiro.ledger.domain.Money
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.properties.Delegates
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The tests that justify the design. Everything above them is style; these are
 * correctness under the conditions where naive ledgers actually break.
 */
class ConcurrentPostingIT : AbstractIntegrationTest() {

    @Autowired private lateinit var useCase: PostTransactionUseCase
    @Autowired private lateinit var jdbc: JdbcClient

    // AccountId is a value class; lateinit does not support inline value class properties.
    private var wallet: AccountId by Delegates.notNull()
    private var merchant: AccountId by Delegates.notNull()
    private val brl = CurrencyCode("BRL")

    @BeforeEach
    fun setUp() {
        jdbc.sql("TRUNCATE outbox_event, idempotency_record, ledger_entry, ledger_transaction, account_balance, account CASCADE").update()
        wallet = createAccount(allowsNegative = false)
        merchant = createAccount(allowsNegative = true)
        fund(wallet, 100_00) // R$ 100.00
    }

    /**
     * 200 concurrent debits of R$ 1.00 against a R$ 100.00 balance.
     *
     * A ledger that checks the balance outside the row lock lets more than 100 through
     * and ends up negative. Exactly 100 must succeed, the rest must be rejected, and
     * the final balance must be zero — not merely non-negative.
     */
    @Test
    fun `concurrent debits never overdraw the account`() {
        val attempts = 200
        val amount = 1_00L
        val succeeded = AtomicInteger()
        val rejected = AtomicInteger()
        val start = CountDownLatch(1)

        Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            repeat(attempts) { i ->
                pool.submit {
                    start.await()
                    try {
                        useCase.post(debit(amount, key = "concurrent-$i"))
                        succeeded.incrementAndGet()
                    } catch (e: DomainError.InsufficientFunds) {
                        rejected.incrementAndGet()
                    }
                }
            }
            start.countDown()
        }

        assertEquals(100, succeeded.get(), "exactly 100 debits of R\$1.00 fit into R\$100.00")
        assertEquals(100, rejected.get())
        assertEquals(0L, balanceOf(wallet))
        assertNoImbalance()
    }

    /**
     * The same idempotency key fired concurrently must produce one transaction, not a
     * race between a unique-constraint violation and a duplicate posting.
     */
    @Test
    fun `concurrent requests with the same idempotency key post exactly once`() {
        val key = "same-key-for-everyone"
        val results = mutableListOf<PostTransactionResult>()
        val start = CountDownLatch(1)

        Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            repeat(32) {
                pool.submit {
                    start.await()
                    synchronized(results) { results += useCase.post(debit(10_00, key)) }
                }
            }
            start.countDown()
        }

        val distinctIds = results.map { it.transactionId }.distinct()
        assertEquals(1, distinctIds.size, "all callers must observe the same transaction")
        assertEquals(1, results.count { it is PostTransactionResult.Posted })
        assertEquals(1, countTransactions())
        assertEquals(90_00L, balanceOf(wallet))
    }

    /**
     * Two transfers in opposite directions, fired in a tight loop. Locking in payload
     * order deadlocks here within a few iterations; locking by account id does not.
     */
    @Test
    fun `opposing transfers do not deadlock`() {
        fund(merchant, 100_00)
        val failures = AtomicInteger()
        val start = CountDownLatch(1)

        Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            repeat(50) { i ->
                pool.submit {
                    start.await()
                    runCatching { useCase.post(transfer(wallet, merchant, 1_00, "a-$i")) }
                        .onFailure { failures.incrementAndGet() }
                }
                pool.submit {
                    start.await()
                    runCatching { useCase.post(transfer(merchant, wallet, 1_00, "b-$i")) }
                        .onFailure { failures.incrementAndGet() }
                }
            }
            start.countDown()
        }

        assertEquals(0, failures.get(), "no transfer should fail; a deadlock would surface here")
        assertNoImbalance()
    }

    @Test
    fun `posting against an account without a balance row is rejected and rolled back entirely`() {
        val orphan = createAccount(allowsNegative = true)
        jdbc.sql("DELETE FROM account_balance WHERE account_id = ?").param(orphan.value).update()

        assertFailsWith<IllegalStateException> { useCase.post(transfer(orphan, merchant, 5_00, "orphan")) }

        assertEquals(0, countTransactions(), "ledger_transaction must be rolled back")
        val entries = jdbc.sql("SELECT COUNT(*) FROM ledger_entry WHERE account_id IN (?, ?)")
            .params(orphan.value, merchant.value).query(Long::class.java).single()
        assertEquals(0L, entries, "ledger_entry rows must be rolled back")
        assertEquals(0L, balanceOf(merchant))
    }

    @Test
    fun `posting against existing balance rows updates balance and version`() {
        useCase.post(debit(10_00, key = "normal-flow"))

        assertEquals(90_00L, balanceOf(wallet))
        val version = jdbc.sql("SELECT version FROM account_balance WHERE account_id = ?")
            .param(wallet.value).query(Long::class.java).single()
        assertEquals(2L, version, "one bump for the funding, one for the debit")
    }

    /** Global integrity: the sum of every signed entry in the ledger must be zero. */
    private fun assertNoImbalance() {
        val imbalance = jdbc.sql("SELECT COALESCE(SUM(signed_minor_units), 0) FROM ledger_entry")
            .query(Long::class.java).single()
        assertEquals(0L, imbalance, "the ledger as a whole must sum to zero")

        val projectionDrift = jdbc.sql(
            """
            SELECT COUNT(*)
              FROM account_balance b
              LEFT JOIN (
                    SELECT account_id, SUM(signed_minor_units) AS total
                      FROM ledger_entry GROUP BY account_id
              ) e ON e.account_id = b.account_id
             WHERE b.balance_minor <> COALESCE(e.total, 0)
            """.trimIndent(),
        ).query(Long::class.java).single()
        assertTrue(projectionDrift == 0L, "balance projection drifted from the entry log")
    }

    private fun createAccount(allowsNegative: Boolean): AccountId {
        val id = UUID.randomUUID()
        jdbc.sql("INSERT INTO account (id, currency, normal_side, allows_negative) VALUES (?, 'BRL', 1, ?)")
            .params(id, allowsNegative)
            .update()
        return AccountId(id)
    }

    private fun fund(account: AccountId, amount: Long) {
        val funding = createAccount(allowsNegative = true)
        // Tagged with an external reference so countTransactions(), which only counts
        // transactions under test, does not mistake setup funding for test activity.
        useCase.post(transfer(funding, account, amount, "funding-${UUID.randomUUID()}", externalReference = "funding"))
    }

    private fun debit(amount: Long, key: String) = transfer(wallet, merchant, amount, key)

    private fun transfer(
        from: AccountId,
        to: AccountId,
        amount: Long,
        key: String,
        externalReference: String? = null,
    ) = PostTransactionCommand(
        idempotencyKey = key,
        currency = brl,
        entries = listOf(
            PostTransactionCommand.EntryCommand(from, Direction.CREDIT, Money.positiveMinorUnits(amount)),
            PostTransactionCommand.EntryCommand(to, Direction.DEBIT, Money.positiveMinorUnits(amount)),
        ),
        externalReference = externalReference,
    )

    private fun balanceOf(account: AccountId): Long =
        jdbc.sql("SELECT balance_minor FROM account_balance WHERE account_id = ?")
            .param(account.value)
            .query(Long::class.java)
            .single()

    private fun countTransactions(): Long =
        jdbc.sql("SELECT COUNT(*) FROM ledger_transaction WHERE external_reference IS NULL")
            .query(Long::class.java).single()
}
