package com.hexacore.aidaapplication

import okhttp3.MultipartBody
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface ApiService {
    @Multipart
    @POST("retrieve-data")
    suspend fun sendWavFile(
        @Part file: MultipartBody.Part
    ): ResponseData

    @Multipart
    @POST("retrieve-meeting-recording")
    suspend fun sendMeetingRecording(
        @Part file: MultipartBody.Part
    ): ResponseData

    @Multipart
    @POST("retrieve-summarized-meeting")
    suspend fun sendMeetingFile(
        @Part file: MultipartBody.Part
    ): ResponseData

    @FormUrlEncoded
    @POST("retrieve-search-results")
    suspend fun sendSearchQuery(
        @Field("query") query: String
    ): ResponseData

    @POST("test-connection")
    suspend fun testConnection(): ResponseData
}
