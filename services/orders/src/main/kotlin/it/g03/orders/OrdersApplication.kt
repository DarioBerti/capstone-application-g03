package it.g03.orders

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

// Spring Boot entry point for the Orders service
// @SpringBootApplication enables component scanning and auto-configuration starting from this package
// @EnableScheduling activates @Scheduled tasks (the order timeout sweeper poller; the outbox relay is now external — Debezium CDC)
@SpringBootApplication
@EnableScheduling
class OrdersApplication

// main: launches the Spring application context and embedded server
fun main(args: Array<String>) {
	runApplication<OrdersApplication>(*args)
}
