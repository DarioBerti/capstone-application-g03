package it.g03.notifications.controller

import it.g03.notifications.events.NotificationRequested
import it.g03.notifications.events.NotificationsListener
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController   // exposes this class as a REST controller (will return values as HTTP responses)

@RequestMapping("/api/notifications") // sets the base path for every endpoint in this controller
class NotificationsController(private val listener: NotificationsListener) {

    // GET /api/notifications -> returns the notifications this instance has "sent" so far
    @GetMapping
    fun recent(): List<NotificationRequested> = listener.recent.toList()
}
