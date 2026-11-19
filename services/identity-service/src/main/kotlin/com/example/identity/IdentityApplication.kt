package com.example.identity

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class IdentityApplication

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<IdentityApplication>(*args)
}
