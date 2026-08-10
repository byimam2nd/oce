<div align="center">

<img src="https://raw.githubusercontent.com/byimam2nd/oce/refs/heads/master/assets/logo.svg" width="140" height="140" alt="OCE Logo">

# 🌊 OCE — Open Cloudstream Extension

### Modular, Stable, Multi-Provider Extension for CloudStream 3

[![Build Status](https://github.com/byimam2nd/oce/actions/workflows/ci-cd.yml/badge.svg)](https://github.com/byimam2nd/oce/actions)
[![Platform: CloudStream](https://img.shields.io/badge/Platform-CloudStream-6200EE?style=flat-square&logo=android)](https://cloudstream.cf)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg?style=flat-square)](https://www.gnu.org/licenses/gpl-3.0)
[![Version](https://img.shields.io/github/v/release/byimam2nd/oce?style=flat-square&color=brightgreen)](https://github.com/byimam2nd/oce/releases)
[![Release](https://img.shields.io/github/v/release/byimam2nd/oce?display_name=release&style=flat-square&color=ff4b2b)](https://github.com/byimam2nd/oce/releases/latest)

</div>

---

## 📦 Providers

| Provider | Domain | Type | Status |
|---|---|---|---|
| **Anichin** | anichin.cafe | Donghua Anime | ✅ Stable |
| **Animasu** | v2.animasu.work | Anime | ✅ Stable |
| **Donghuastream** | donghuastream.org | Donghua Streaming | ✅ Stable |
| **Dutamovie21** | simplycufflinks.com | Movie, Series, Anime | ✅ Stable |
| **IndoDrama21** | indodr21.putar.in | Movie, Asian Drama | ✅ Stable |
| **LayarKaca21** | tv10.lk21official.cc | Movie, Series | ✅ Stable |
| **Samehadaku** | samehadaku.biz | Anime Sub Indo | ✅ Stable |

---

## 🔧 Extractor Support

| Extractor | Host | Method |
|---|---|---|
| **AbyssPlayer** | abyssplayer.com | Decrypt API (hydrax) |
| **AWSStream** | awstream.net | POST hash → videoSource |
| **BloggerVideo** | blogger.com | Video element extraction |
| **Dhcplay** | dhcplay.com | WebViewResolver + packed JS |
| **Filedon** | filedon.co | Direct extraction |
| **Gdplayer** | gdplayer.to | API kaken token |
| **Lk21Player** | playeriframe.sbs | AJAX + iframe fallback |
| **LuluStream** | luluvdo.com | POST form → vplayer |
| **MegaPlay** | megaplay.buzz | API data-id → getSources |
| **Minochinos** | minochinos.com | Packed JS + JW Player |
| **Movearnpre** | movearnpre.com | Packed JS + JW Player |
| **PlayPutarIn** | play.putar.in | URL parameter forward |
| **PlayStreamplay** | play.streamplay.co.in | Iframe extraction |
| **Rumble** | rumble.com | Regex extraction |
| **ShortIcu** | short.icu | Redirect follow |
| **StreamHG** | hgcloud.to | WebViewResolver + packed JS |
| **StreamRuby** | rubyvidhub.com | Direct pattern |
| **Vidguardto** | listeamed.net | Rhino JS + sigDecode |
| **Voe** | voe.sx | Regex m3u8 extraction |
| **Wishfast** | wishfast.to | Packed JS + file: pattern |
| **Xtwap** | xtwap.top | JW Player → play.php → m3u8 |
| **Odnoklassniki** | ok.ru | API extraction |
| **YouTube** | youtube.com | Trailer extraction |

---

## 🏗️ Architecture

```
BaseProvider/src/main/kotlin/com/baseprovider/
│
├── core/           ← ProviderCloudstream (MainAPI), DetailPageScrapper, Flow
├── config/         ← ProviderConfig + per-provider JSON (selector & options)
├── collector/      ← LinkCollector, FallbackPipeline
├── cache/          ← ExpiringCache
├── network/        ← HttpClient wrapper + CircuitBreaker
├── extractor/      ← Video host extractors (JS Packer, API, WebView)
├── log/            ← Telegram logging (owner tagged)
├── model/          ← Utility: attribute extraction, text cleaning
├── ExtractorRegistry.kt  ← Registered extractor list
```

Each provider is **config-driven** — a thin module (`ProviderAnichin/`) with only 2 files + a JSON config:
- `ProviderName.kt` — extends `ProviderCloudstream()`
- `ProviderNamePlugin.kt` — registers main API + extractors
- `BaseProvider/.../config/<name>.json` — selectors & options (remote-first via ConfigRegistry)

See [docs/LEARNING_GUIDE.md](docs/LEARNING_GUIDE.md) for the full architecture guide.

---

## 🧪 Build & CI

```bash
./gradlew make                    # Build all .cs3 plugins
./gradlew makePluginsJson         # Generate plugins.json
```

**Two distribution channels:**
- **Stable** — GitHub Releases (manually tagged)
- **Beta** — Builds branch (auto-built on every push)

---

## 📋 Requirements

- Android 5.0+ (API 21)
- CloudStream 3 (latest)

---

## 🤝 Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

---

## ⚖️ License

GNU General Public License v3.0 — see [LICENSE](LICENSE).

---

<div align="center">
  <b>Managed by <a href="https://github.com/byimam2nd">imam2nd</a></b>
  <br>
  <i>For the community, by the community.</i>
</div>
