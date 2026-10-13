package com.example.todo.infrastructure.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(
    name = "idempotency_records",
    indexes = [Index(name = "idx_idempotency_expiration", columnList = "expires_at")],
)
class IdempotencyRecordJpaEntity(
    @Id
    @Column(length = 255)
    var key: String,

    @Column(nullable = false, length = 64)
    var requestFingerprint: String,

    @Column(nullable = false, columnDefinition = "STRING(MAX)")
    var responseSnapshot: String,

    @Column(nullable = false)
    var expiresAt: Instant,
)
