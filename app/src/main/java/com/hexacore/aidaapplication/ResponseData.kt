package com.hexacore.aidaapplication
data class RequestData(val text: String)

data class ResponseData(
    val response: String,
    val key: String,
    val transcript: String // optional if you included it
)
