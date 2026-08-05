package dev.danilocordeiro.ledger.domain

import java.util.UUID

@JvmInline
value class AccountId(val value: UUID) {
    companion object {
        fun of(raw: String): AccountId = AccountId(UUID.fromString(raw))
    }
}

/**
 * The side on which an account increases. Assets and expenses are debit-normal;
 * liabilities, equity and revenue are credit-normal.
 *
 * This matters because "balance" is meaningless without it: a customer wallet is a
 * liability from the platform's point of view, so a *credit* increases what the
 * customer owns. Collapsing this into one signed number is the most common way
 * ledgers end up reporting inverted balances.
 */
enum class NormalSide(val sign: Int) {
    DEBIT(1),
    CREDIT(-1);

    companion object {
        fun fromSign(sign: Int): NormalSide =
            entries.firstOrNull { it.sign == sign } ?: error("invalid normal side sign: $sign")
    }
}

data class Account(
    val id: AccountId,
    val currency: CurrencyCode,
    val normalSide: NormalSide,
    /** Whether the account may go below zero. System/clearing accounts usually may. */
    val allowsNegative: Boolean,
)

/**
 * Signed sum of entries in raw ledger terms (debit positive). [available] flips it into
 * the account's own frame of reference.
 */
data class AccountBalance(
    val accountId: AccountId,
    val signedMinorUnits: Long,
    val version: Long,
) {
    fun available(normalSide: NormalSide): Money =
        Money.ofMinorUnits(signedMinorUnits * normalSide.sign)
}
