package dev.danilocordeiro.ledger.config

import dev.danilocordeiro.ledger.application.port.output.AccountRepository
import dev.danilocordeiro.ledger.application.port.output.ClockPort
import dev.danilocordeiro.ledger.application.port.output.IdempotencyRepository
import dev.danilocordeiro.ledger.application.port.output.LedgerRepository
import dev.danilocordeiro.ledger.application.port.output.OutboxRepository
import dev.danilocordeiro.ledger.application.port.output.UnitOfWork
import dev.danilocordeiro.ledger.application.service.PostTransactionService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant

/**
 * Wiring lives here so the application layer stays free of Spring annotations.
 * PostTransactionService is a plain class with a constructor; that is what makes its
 * unit test a five-line MockK setup with no context to start.
 */
@Configuration
class BeanConfiguration {

    @Bean
    fun clockPort(clock: Clock) = ClockPort { Instant.now(clock) }

    @Bean
    fun systemClock(): Clock = Clock.systemUTC()

    /**
     * The transaction boundary is deliberately not @Transactional on the service.
     * Besides keeping Spring out of the application layer, it sidesteps the proxy
     * self-invocation trap: an internal call to a @Transactional method silently runs
     * without a transaction, which in a ledger means partially applied money movement.
     */
    @Bean
    fun unitOfWork(template: TransactionTemplate) = object : UnitOfWork {
        override fun <T> inTransaction(block: () -> T): T =
            template.execute { block() } ?: error("transaction returned null")
    }

    @Bean
    fun postTransactionUseCase(
        accounts: AccountRepository,
        ledger: LedgerRepository,
        idempotency: IdempotencyRepository,
        outbox: OutboxRepository,
        unitOfWork: UnitOfWork,
        clock: ClockPort,
    ) = PostTransactionService(accounts, ledger, idempotency, outbox, unitOfWork, clock)
}
