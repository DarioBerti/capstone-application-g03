package it.g03.orders.service

/**
 * Domain exceptions for the checkout flow, mapped to HTTP 409 in the GlobalExceptionHandler. Both are STATE
 * conflicts (the request is well-formed but the booking is not in a state that allows it), not bad input.
 */

// The backend-owned hold window has elapsed: the order is no longer payable. The expiry sweeper will reject it.
class CheckoutWindowExpiredException(message: String) : RuntimeException(message)

// The booking cannot be paid right now (no reserved outbound leg, or a reservation is still settling).
class BookingNotPayableException(message: String) : RuntimeException(message)
