package com.example.aichat.data.model

data class ModelInfo(
    val id: String,
    val name: String,
    val provider: String,

    val isFree: Boolean = false,
    val supportsVision: Boolean = false,
    val supportsImageGeneration: Boolean = false,
    val recommended: Boolean = false,

    val contextLength: Long = 0L
)
