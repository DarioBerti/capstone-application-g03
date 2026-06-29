package it.g03.orders.controller

import it.g03.orders.dto.ErrorResponse
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Centralizes domain-exception → HTTP mapping for every controller in this service, so controllers
 * stay HTTP-only and the mapping isn't duplicated per controller.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    // A missing order/resource → 404 with a small JSON error body.
    @ExceptionHandler(NoSuchElementException::class)
    fun notFound(e: NoSuchElementException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(e.message))

    // An invalid request (blank tripCode / non-positive qty / missing sub) → 400 with a small JSON error body.
    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(e: IllegalArgumentException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(e.message))

    /**
     * A concurrent POST that loses the UNIQUE(idempotency_key) race → 409 Conflict. The common sequential
     * retry is handled earlier by the fast-path replay in OrderService; this only fires for two in-flight
     * requests sharing a key, and a subsequent retry resolves to the committed order.
     */
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun conflict(e: DataIntegrityViolationException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("duplicate request in flight (idempotency key); retry to fetch the existing order"))
}
