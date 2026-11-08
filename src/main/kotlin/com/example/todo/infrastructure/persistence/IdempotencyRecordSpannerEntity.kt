package com.example.todo.infrastructure.persistence

import com.google.cloud.spring.data.spanner.core.mapping.Column
import com.google.cloud.spring.data.spanner.core.mapping.PrimaryKey
import com.google.cloud.spring.data.spanner.core.mapping.Table
import java.time.Instant

@Table(name = "idempotency_records")
class IdempotencyRecordSpannerEntity(
    @PrimaryKey
    @Column(name = "key")
    var key: String,

    @Column(name = "request_fingerprint")
    var requestFingerprint: String,

    @Column(name = "response_snapshot")
    var responseSnapshot: String,

    @Column(name = "expires_at")
    var expiresAt: Instant,
)
