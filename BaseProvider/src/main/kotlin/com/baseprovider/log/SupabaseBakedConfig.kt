package com.baseprovider.log

object SupabaseBakedConfig {
    const val URL: String = ""
    const val ANON_KEY: String = ""

    // VersionCode plugin, sama dengan OCE_VERSION (epoch menit) yang dipakai
    // ci-cd.yml & release.yml. Dikirim ke setiap baris `logs` sebagai
    // plugin_version supaya log bisa diatribusikan ke build tertentu —
    // tanpa itu, "fix tidak berefek" tidak bisa dibedakan dari
    // "user masih pakai APK lama".
    const val PLUGIN_VERSION: String = ""
}
