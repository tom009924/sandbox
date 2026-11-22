package com.example.todo

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class TodoApplication

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<TodoApplication>(*args)
}
