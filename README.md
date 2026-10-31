# CS3xHermesAdult

CloudStream extensions for adult/NSFW content.

## Plugins

| Plugin | Status | Description |
|--------|--------|-------------|
| JavHey | ✅ Online | JAV streaming with Indonesian subtitles |

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
