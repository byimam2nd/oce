package com.baseprovider.config

val CONFIG_MAP: Map<String, ProviderConfig> = mapOf(
    "Anichin" to ANICHIN,
    "Animasu" to ANIMASU,
    "Donghuastream" to DONGHUASTREAM,
    "LayarKaca21" to LAYARKACA21,
    "IndoDrama21" to INDODRAMA21,
    "Dutamovie21" to DUTAMOVIE21,
    "Pencurimovie" to PENCURIMOVIE,
    "Samehadaku" to SAMEHADAKU,
)

fun providerConfig(id: String): ProviderConfig =
    CONFIG_MAP[id] ?: GLOBAL_CONFIG
