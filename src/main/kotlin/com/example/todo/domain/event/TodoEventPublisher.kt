package com.example.todo.domain.event

interface TodoEventPublisher {
    fun publish(event: TodoEvent)
}

