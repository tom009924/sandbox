package com.example.todo.domain.exception

class ActiveTodosLimitExceededException(
    val limit: Int,
) : RuntimeException("Active todos limit of $limit has been reached")

