package it.g03.payments.gateway.paypal

import it.g03.payments.gateway.LineItem

/**
 * Builds a PayPal `purchase_units[0]` from the booking legs — shared by the authorize gateway and the Card-Fields
 * session provider so both describe the order identically. With items, PayPal requires
 * amount.value == breakdown.item_total == Σ(items), so item_total is computed and used for both; the invariant
 * always holds (and equals the order amount Orders derives from the same legs). With no items, the total alone.
 */
internal fun purchaseUnitFor(amountCents: Long, currency: String, items: List<LineItem>): PurchaseUnit {
    if (items.isEmpty()) return PurchaseUnit(Amount(currency, formatPayPalAmount(amountCents)))
    val itemTotal = formatPayPalAmount(items.sumOf { it.unitAmountCents * it.quantity })
    val payPalItems = items.map { Item(it.name, it.quantity.toString(), Amount(currency, formatPayPalAmount(it.unitAmountCents))) }
    return PurchaseUnit(
        amount = Amount(currency, itemTotal, Breakdown(Amount(currency, itemTotal))),
        items = payPalItems,
    )
}

internal fun formatPayPalAmount(amountCents: Long): String = "%d.%02d".format(amountCents / 100, amountCents % 100)
