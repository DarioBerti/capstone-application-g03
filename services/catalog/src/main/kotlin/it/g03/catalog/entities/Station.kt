package it.g03.catalog.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * A station a trip runs between (reference/master data), keyed by its short station code (e.g. 'TRN').
 *
 * Plain `class`, not `data class`: a JPA-managed entity has identity semantics, not value semantics —
 * deriving equals/hashCode/copy over its columns is a foot-gun (see root CLAUDE.md). DTOs stay immutable.
 * Property defaults give the JPA no-arg constructor (with `kotlin("plugin.jpa")`) something to call.
 */
@Entity
@Table(name = "stations")
class Station(
    @Id
    @Column(name = "code", length = 8)
    val code: String = "",

    @Column(nullable = false, length = 128)
    val name: String = "",

    @Column(nullable = false, length = 128)
    val city: String = "",

    @Column(nullable = false, length = 2)
    val country: String = "",
)
