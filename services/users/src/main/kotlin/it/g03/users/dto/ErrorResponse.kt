package it.g03.users.dto

// Typed error body returned by GlobalExceptionHandler. Serializes to {"error": "..."}.
data class ErrorResponse(val error: String?)
