package com.example.todo.infrastructure.persistence

import com.google.gson.Gson
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SpannerConfig {
    @Bean
    @ConditionalOnMissingBean
    fun gson(): Gson = Gson()
}
