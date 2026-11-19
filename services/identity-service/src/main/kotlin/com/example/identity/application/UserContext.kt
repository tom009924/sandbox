package com.example.identity.application

import java.util.UUID

interface UserContext {
    fun currentUserId(): UUID
}
