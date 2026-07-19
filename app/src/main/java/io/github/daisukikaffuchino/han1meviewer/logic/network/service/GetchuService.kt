package io.github.daisukikaffuchino.han1meviewer.logic.network.service

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.POST
import retrofit2.http.Query

interface GetchuService {

    @GET("all/month_title.html")
    suspend fun getPreviewList(
        @Query("genre") genre: String = "anime_dvd",
        @Query("gage") gage: String = "adult",
        @Query("year") year: String,
        @Query("month") month: String,
        @Query("gc") gc: String = "gc",
    ): Response<ResponseBody>

    @GET("item/{id}/")
    suspend fun getPreviewDetail(
        @Path("id") id: String,
        @Query("gc") gc: String = "gc",
    ): Response<ResponseBody>

    @FormUrlEncoded
    @POST("util/GetchuSearch/GetchuSearchAjax.php")
    suspend fun getSeriesItems(
        @Field("product_id_array") productIdArray: String = "",
        @Field("parent_id_array") parentIdArray: String,
        @Field("genre") genre: String = "anime_dvd",
        @Field("sub_genre_array") subGenreArray: String = "",
        @Field("NA_sub_genre_array") naSubGenreArray: String = "",
        @Field("sub_genre_perfect_matching") subGenrePerfectMatching: String = "",
        @Field("brand_id_array") brandIdArray: String = "",
        @Field("age") age: String = "",
        @Field("stock_flag") stockFlag: String = "",
        @Field("sort_condition") sortCondition: String = "release_date",
        @Field("sort_order") sortOrder: String = "asc",
        @Field("limit_count") limitCount: String = "30",
        @Field("limit_count_lower") limitCountLower: String = "1",
        @Field("image_exist") imageExist: String = "",
        @Field("start_date") startDate: String = "",
        @Field("end_date") endDate: String = "",
        @Field("novelty_flag") noveltyFlag: String = "",
        @Field("template_html") templateHtml: String = "item-series/item-series.html",
        @Field("paging") paging: String = "",
        @Field("page_size") pageSize: String = "",
        @Field("javascript_id") javascriptId: String = "",
        @Field("search_word") searchWord: String = "",
        @Field("limitless") limitless: String = "1",
        @Field("lower_limit") lowerLimit: String = "",
        @Field("upper_limit") upperLimit: String = "",
        @Field("image_size") imageSize: String = "s",
        @Field("add_query") addQuery: String = "",
    ): Response<ResponseBody>
}
