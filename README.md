# CS3xHermesAdult

CloudStream extensions for adult/NSFW content.

## Plugins

| Plugin | Status | Description |
|--------|--------|-------------|
| JavHey | ✅ Online | JAV streaming with Indonesian subtitles |
| FilmSemi | ✅ Online | MovieBox, Idlix, VidSrc — **third-party code, see [Attribution](#attribution)** |

## Attribution

### FilmSemi  (sebelumnya AdiFilmSemi)

Not written for this repository. Copied from:

- Source: <https://github.com/bikinveo-hash/RepairPremium_Repo/tree/main/AdiFilmSemi>
- Binary it was published as: <https://raw.githubusercontent.com/bikinveo-hash/RepairPremium_Repo/builds/AdiFilmSemi.cs3> (v17)
- Original author, per its own `build.gradle.kts`: **aldry84** (`authors = listOf("aldry84")`, package `com.michat88`)

The upstream repository carries **no LICENSE and no README**, so there is no explicit
redistribution terms to follow. The files were copied verbatim apart from a credit
header added to each of them; no behavioural change was made. Keep that header if
you fork or reuse this code.

Note that the upstream lineage is itself unclear: `bikinveo-hash` publishes the
plugin, the package and repository description name `michat88`, and the plugin
credits `aldry84`. If you are the author and want the attribution changed or the
plugin removed, open an issue.

## How to Install

1. Open CloudStream → Settings → Extensions
2. Add repository URL:
   ```
   https://raw.githubusercontent.com/diioradhitya/CS3xHermesAdult/main/repo.json
   ```
3. Install the plugin and restart CloudStream

## Building from Source

Requires: JDK 17, Android SDK 35

```bash
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew make
```

## Adding New Plugins

1. Create `<Name>Provider/` folder at root
2. Add `build.gradle.kts` (see JavHeyProvider for template)
3. Create `src/main/kotlin/com/<name>/` with `*Provider.kt` and `*ProviderPlugin.kt`
4. Build: `./gradlew :<Name>Provider:make`
