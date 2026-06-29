package it.g03.orders.dto

// Typed error body returned by GlobalExceptionHandler. Serializes to {"error": "..."},
// preserving the previous untyped map shape.
data class ErrorResponse(val error: String?)
