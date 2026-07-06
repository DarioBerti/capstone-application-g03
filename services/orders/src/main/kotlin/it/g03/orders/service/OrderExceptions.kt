package it.g03.orders.service

/**
 * Domain exceptions for the checkout flow, mapped to HTTP in the GlobalExceptionHandler. The two window/payable
 * exceptions are STATE conflicts (409 — well-formed request, wrong booking state); [BadPriceQuoteException] is
 * bad INPUT (400 — the client sent an invalid price quote).
 */

// The backend-owned hold window has elapsed: the order is no longer payable. The expiry sweeper will reject it.
class CheckoutWindowExpiredException(message: String) : RuntimeException(message)

// The booking cannot be paid right now (no reserved outbound leg, or a reservation is still settling).
class BookingNotPayableException(message: String) : RuntimeException(message)

// The add-leg price quote is malformed, tampered, expired, or issued for a different trip → the leg cannot be
// priced. Bad input (400), not a state conflict.
class BadPriceQuoteException(message: String) : RuntimeException(message)
