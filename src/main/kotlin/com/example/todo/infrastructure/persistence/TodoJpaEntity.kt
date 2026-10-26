package com.example.todo.infrastructure.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import com.example.todo.domain.model.TodoPriority
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(
    name = "todos",
    indexes = [
        Index(name = "idx_todos_user_completed", columnList = "user_id, completed"),
        Index(name = "idx_todos_user_priority", columnList = "user_id, priority"),
    ],
)
@Suppress("LongParameterList")
class TodoJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,

    @Column(nullable = false)
    var userId: UUID,

    @Column(nullable = false)
    var title: String,

    @Column(nullable = false)
    var completed: Boolean = false,

    @Column
    var description: String? = null,

    @Column
    var dueDate: LocalDate? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var priority: TodoPriority = TodoPriority.MEDIUM,

    @Column
    var estimatedMinutes: Int? = null,
)
