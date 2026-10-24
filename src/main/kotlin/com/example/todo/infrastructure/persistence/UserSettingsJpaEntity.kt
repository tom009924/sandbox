package com.example.todo.infrastructure.persistence

import com.example.todo.domain.model.TodoPriority
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "user_settings")
class UserSettingsJpaEntity(
    @Id
    var userId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var defaultPriority: TodoPriority = TodoPriority.MEDIUM,

    @Column(nullable = true)
    var defaultEstimatedMinutes: Int? = null,

    @Column(nullable = true)
    var maxActiveTodos: Int? = null,
)

