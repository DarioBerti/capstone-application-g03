package it.g03.notifications

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

// Spring Boot entry point for the Notifications service
// @SpringBootApplication enables component scanning and auto-configuration starting from this package
// @EnableScheduling drives the SSE keep-alive heartbeat (OrderStreamHub) so idle streams survive proxy timeouts
@SpringBootApplication
@EnableScheduling
class NotificationsApplication

// main: launches the Spring application context and embedded server
fun main(args: Array<String>) {
	runApplication<NotificationsApplication>(*args)
}
