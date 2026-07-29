package com.assetsking.model

@JvmInline
value class Money(val cents: Long) {
    operator fun plus(other: Money) = Money(cents + other.cents)
    operator fun minus(other: Money) = Money(cents - other.cents)
    operator fun unaryMinus() = Money(-cents)

    companion object {
        val ZERO = Money(0)
        fun yuan(yuan: Long, cents: Int = 0) = Money(yuan * 100 + cents)
    }
}
