package com.example.todo.application

import java.util.UUID

interface UserContext {
    fun currentUserId(): UUID
}

