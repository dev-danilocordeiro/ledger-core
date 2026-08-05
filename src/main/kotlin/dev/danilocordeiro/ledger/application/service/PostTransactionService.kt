package dev.danilocordeiro.ledger.application.service

import dev.danilocordeiro.ledger.application.port.input.PostTransactionCommand
import dev.danilocordeiro.ledger.application.port.input.PostTransactionResult
import dev.danilocordeiro.ledger.application.port.input.PostTransactionUseCase
import dev.danilocordeiro.ledger.application.port.output.AccountRepository
import dev.danilocordeiro.ledger.application.port.output.ClockPort
import dev.danilocordeiro.ledger.application.port.output.IdempotencyRaceLost
import dev.danilocordeiro.ledger.application.port.output.IdempotencyRecord
import dev.danilocordeiro.ledger.application.port.output.IdempotencyRepository
import dev.danilocordeiro.ledger.application.port.output.LedgerRepository
import dev.danilocordeiro.ledger.application.port.output.OutboxEvent
import dev.danilocordeiro.ledger.application.port.output.OutboxRepository
import dev.danilocordeiro.ledger.application.port.output.UnitOfWork
import dev.danilocordeiro.ledger.domain.Account
import dev.danilocordeiro.ledger.domain.DomainError
import dev.danilocordeiro.ledger.domain.Entry
import dev.danilocordeiro.ledger.domain.LedgerTransaction
import dev.danilocordeiro.ledger.domain.TransactionId
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/**
 * Posts a balanced transaction exactly once.
 *
 * Ordering inside the unit of work is not arbitrary:
 *   1. lock accounts (deterministic id order, inside the port)
 *   2. validate against *locked* balances
 *   3. append entries
 *   4. apply balance deltas
 *   5. append outbox event
 *   6. persist the idempotency record last
 *
 * Reading balances before locking would make the overdraft check a lie under
 * concurrency: two requests could both read a sufficient balance and both proceed.
 * Writing the idempotency record last means a crash mid-flight rolls everything back
 * together and the client's retry is a genuine first attempt rather than a replay of a
 * transaction that never landed.
 */
class PostTransactionService(
    private val accounts: AccountRepository,
    private val ledger: LedgerRepository,
    private val idempotency: IdempotencyRepository,
    private val outbox: OutboxRepository,
    private val unitOfWork: UnitOfWork,
    private val clock: ClockPort,
) : PostTransactionUseCase {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun post(command: PostTransactionCommand): PostTransactionResult {
        val fingerprint = fingerprintOf(command)

        // Fast path outside the transaction: most replays are client retries after a
        // timeout, and answering them without taking row locks keeps hot accounts free.
        resolveReplay(command.idempotencyKey, fingerprint)?.let { return it }

        return try {
            unitOfWork.inTransaction { execute(command, fingerprint) }
        } catch (e: IdempotencyRaceLost) {
            // Two concurrent requests carried the same key; the loser re-reads the
            // winner's committed record instead of failing the caller.
            log.info("idempotency race lost, resolving as replay key={}", e.key)
            resolveReplay(command.idempotencyKey, fingerprint)
                ?: throw IllegalStateException("idempotency race lost but no record found for ${e.key}", e)
        }
    }

    private fun execute(
        command: PostTransactionCommand,
        fingerprint: String,
    ): PostTransactionResult.Posted {
        val accountIds = command.entries.map { it.accountId }.toSet()
        val loadedAccounts = accounts.findAll(accountIds)
        val missing = accountIds - loadedAccounts.keys
        if (missing.isNotEmpty()) throw DomainError.AccountNotFound(missing.first())

        loadedAccounts.values.forEach { account ->
            if (account.currency != command.currency) {
                throw DomainError.CurrencyMismatch(
                    "account ${account.id.value} holds ${account.currency.code}, " +
                        "transaction is ${command.currency.code}",
                )
            }
        }

        val now = clock.now()
        val transaction = LedgerTransaction.post(
            id = TransactionId.new(),
            currency = command.currency,
            entries = command.entries.map { Entry(it.accountId, it.direction, it.amount) },
            occurredAt = now,
            externalReference = command.externalReference,
        )

        val balances = ledger.lockAndLoadBalances(accountIds)
        val deltas = transaction.netByAccount()
        assertSolvency(loadedAccounts, balances, deltas)

        ledger.append(transaction)
        ledger.applyBalanceDeltas(deltas)
        outbox.append(
            OutboxEvent(
                aggregateId = transaction.id.value.toString(),
                eventType = "ledger.transaction.posted.v1",
                payloadJson = TransactionPostedPayload.render(transaction),
            ),
        )
        idempotency.save(
            IdempotencyRecord(
                key = command.idempotencyKey,
                requestFingerprint = fingerprint,
                transactionId = transaction.id,
                createdAt = now,
            ),
        )

        log.atInfo()
            .addKeyValue("transaction_id", transaction.id.value)
            .addKeyValue("entry_count", transaction.entries.size)
            .addKeyValue("currency", transaction.currency.code)
            .log("transaction posted")

        return PostTransactionResult.Posted(transaction.id, now)
    }

    private fun assertSolvency(
        accounts: Map<dev.danilocordeiro.ledger.domain.AccountId, Account>,
        balances: Map<dev.danilocordeiro.ledger.domain.AccountId, dev.danilocordeiro.ledger.domain.AccountBalance>,
        deltas: Map<dev.danilocordeiro.ledger.domain.AccountId, Long>,
    ) {
        deltas.forEach { (accountId, delta) ->
            val account = accounts.getValue(accountId)
            if (account.allowsNegative) return@forEach

            val current = balances[accountId]?.signedMinorUnits ?: 0L
            val projected = Math.addExact(current, delta) * account.normalSide.sign
            if (projected < 0) {
                throw DomainError.InsufficientFunds(
                    accountId = accountId,
                    availableMinorUnits = current * account.normalSide.sign,
                    requestedMinorUnits = -delta * account.normalSide.sign,
                )
            }
        }
    }

    private fun resolveReplay(key: String, fingerprint: String): PostTransactionResult.Replayed? {
        val existing = idempotency.find(key) ?: return null
        if (existing.requestFingerprint != fingerprint) throw DomainError.IdempotencyConflict(key)
        return PostTransactionResult.Replayed(existing.transactionId, existing.createdAt)
    }

    /**
     * A key is only idempotent if it is bound to a payload. Without the fingerprint a
     * client that reuses a key for a different transfer would get back a success for an
     * operation that never happened — the failure mode this whole mechanism exists to
     * prevent. Entries are sorted so that ordering differences do not produce a false
     * conflict.
     */
    private fun fingerprintOf(command: PostTransactionCommand): String {
        val canonical = buildString {
            append(command.currency.code).append('|')
            append(command.externalReference.orEmpty()).append('|')
            command.entries
                .map { "${it.accountId.value}:${it.direction.sign}:${it.amount.minorUnits}" }
                .sorted()
                .joinTo(this, separator = ",")
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

private object TransactionPostedPayload {
    fun render(transaction: LedgerTransaction): String {
        val entries = transaction.entries.joinToString(",") { entry ->
            """{"accountId":"${entry.accountId.value}",""" +
                """"direction":"${entry.direction.name}",""" +
                """"amountMinorUnits":${entry.amount.minorUnits}}"""
        }
        return """{"transactionId":"${transaction.id.value}",""" +
            """"currency":"${transaction.currency.code}",""" +
            """"occurredAt":"${transaction.occurredAt}",""" +
            """"externalReference":${transaction.externalReference?.let { "\"$it\"" } ?: "null"},""" +
            """"entries":[$entries]}"""
    }
}
