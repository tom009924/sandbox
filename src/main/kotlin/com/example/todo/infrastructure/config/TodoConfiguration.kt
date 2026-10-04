package com.example.todo.infrastructure.config

import com.example.todo.application.TodoService
import com.example.todo.domain.repository.TodoRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class TodoConfiguration {
    @Bean
    fun todoService(todoRepository: TodoRepository) = TodoService(todoRepository)
}
