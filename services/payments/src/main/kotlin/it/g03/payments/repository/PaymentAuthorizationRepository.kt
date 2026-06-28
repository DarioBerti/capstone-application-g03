package it.g03.payments.repository

import it.g03.payments.entities.PaymentAuthorization
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

// JpaRepository<Entity, IdType> is a Spring Data interface that provides CRUD operations and query methods (for the PaymentAuthorization entity in this case); the IdType is UUID because the primary key is a UUID. Spring Data will generate the implementation at runtime
interface PaymentAuthorizationRepository : JpaRepository<PaymentAuthorization, UUID> {

    // Idempotency lookup: one authorization outcome per order
    // Spring Data derives the query from the method name: find the authorization whose orderId matches
    fun findByOrderId(orderId: String): PaymentAuthorization?
}
