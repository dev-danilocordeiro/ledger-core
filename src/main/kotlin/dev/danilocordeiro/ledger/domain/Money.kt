package dev.danilocordeiro.ledger.domain

/**
 * Money is stored as minor units (cents) in a Long. Never BigDecimal, never Double.
 *
 * Rationale: BigDecimal carries a scale that silently differs across JDBC drivers,
 * JSON parsers and arithmetic operations, producing rounding drift that only surfaces
 * during reconciliation — months later, in production. A Long of minor units has
 * exactly one representation for a given amount.
 *
 * Long holds ~9.2e18 minor units, i.e. ~92 quadrillion currency units at two decimal
 * places. Every arithmetic operation uses Math.*Exact so overflow throws instead of
 * silently wrapping into a negative balance.
 */
@JvmInline
value class Money private constructor(val minorUnits: Long) {

    operator fun plus(other: Money) = Money(Math.addExact(minorUnits, other.minorUnits))
    operator fun minus(other: Money) = Money(Math.subtractExact(minorUnits, other.minorUnits))

    val isPositive: Boolean get() = minorUnits > 0
    val isZero: Boolean get() = minorUnits == 0L

    override fun toString(): String {
        val sign = if (minorUnits < 0) "-" else ""
        val abs = Math.abs(minorUnits)
        return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
    }

    companion object {
        val ZERO = Money(0)

        fun ofMinorUnits(value: Long): Money = Money(value)

        /** Fails fast: a non-positive amount in a ledger entry is always a caller bug. */
        fun positiveMinorUnits(value: Long): Money {
            require(value > 0) { "amount must be strictly positive, got $value" }
            return Money(value)
        }
    }
}

/**
 * ISO-4217 code as a value class rather than java.util.Currency: the JDK type pulls in
 * locale data we do not need and cannot represent crypto or internal accounting units.
 */
@JvmInline
value class CurrencyCode(val code: String) {
    init {
        require(code.length == 3 && code.all { it in 'A'..'Z' }) { "invalid currency code: $code" }
    }
}
