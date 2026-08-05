package dev.danilocordeiro.ledger.integration

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * These tests bypass the application entirely and write raw SQL. That is the point:
 * they verify the database rejects invalid state even when the domain model is not in
 * the call path, which is what makes the invariant real rather than conventional.
 */
class DatabaseInvariantIT : AbstractIntegrationTest() {

    @Autowired private lateinit var jdbc: JdbcClient
    @Autowired private lateinit var tx: TransactionTemplate

    @Test
    fun `postgres rejects an unbalanced transaction at commit`() {
        val account = insertAccount(allowsNegative = true)
        val txId = UUID.randomUUID()

        try {
            tx.executeWithoutResult {
                jdbc.sql("INSERT INTO ledger_transaction (id, currency, occurred_at) VALUES (?, 'BRL', now())")
                    .param(txId).update()
                jdbc.sql(
                    "INSERT INTO ledger_entry (transaction_id, account_id, direction, amount_minor_units) " +
                        "VALUES (?, ?, 1, 5000)",
                ).params(txId, account).update()
                // No matching credit. The deferred trigger fires at COMMIT.
            }
            fail("commit should have been rejected by ledger_entry_must_balance")
        } catch (e: Exception) {
            assertTrue(
                generateSequence(e as Throwable) { it.cause }.any { it.message?.contains("unbalanced") == true },
                "expected the balance trigger to reject the commit, got: ${e.message}",
            )
        }
    }

    @Test
    fun `postgres rejects a negative balance on an account that forbids it`() {
        val account = insertAccount(allowsNegative = false)
        try {
            jdbc.sql("UPDATE account_balance SET balance_minor = -1 WHERE account_id = ?")
                .param(account).update()
            fail("commit should have been rejected by the no_overdraft constraint")
        } catch (e: Exception) {
            assertTrue(
                generateSequence(e as Throwable) { it.cause }.any { it.message?.contains("no_overdraft") == true },
                "expected no_overdraft violation, got: ${e.message}",
            )
        }
    }

    private fun insertAccount(allowsNegative: Boolean): UUID {
        val id = UUID.randomUUID()
        jdbc.sql("INSERT INTO account (id, currency, normal_side, allows_negative) VALUES (?, 'BRL', 1, ?)")
            .params(id, allowsNegative).update()
        return id
    }
}
