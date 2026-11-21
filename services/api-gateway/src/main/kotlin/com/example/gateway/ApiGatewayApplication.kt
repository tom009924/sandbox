package com.example.gateway

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ApiGatewayApplication

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<ApiGatewayApplication>(*args)
}
