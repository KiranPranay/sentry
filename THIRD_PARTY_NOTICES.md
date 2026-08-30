# Third-party notices

Sentry bundles no third-party binaries in this repository. The components below are
fetched at build time by `scripts/fetch-models.sh`, or resolved by Gradle.

## Speech models (fetched, not vendored)

From [Alpha Cephei's Vosk model list](https://alphacephei.com/vosk/models). Check the
licence for each on that page before redistributing a build.

| Model | Used for | Licence as published |
|---|---|---|
| `vosk-model-small-en-us-0.15` | US English recognition | Apache-2.0 |
| `vosk-model-small-en-in-0.4` | Indian English recognition | Apache-2.0 |
| `vosk-model-spk-0.4` | Speaker verification (voice match) | **Not stated** |

`vosk-model-spk-0.4` is the Kaldi CALLHOME diarisation x-vector model, uploaded by
David Snyder in 2018. Its README carries no licence and Alpha Cephei's model list
does not state one, which is why none of these are vendored here and why that one in
particular should be resolved before shipping Sentry anywhere.

## Libraries

| Component | Licence |
|---|---|
| [Vosk](https://github.com/alphacep/vosk-api) (`com.alphacephei:vosk-android`) | Apache-2.0 |
| [JNA](https://github.com/java-native-access/jna) | Apache-2.0 / LGPL-2.1 |
| [Tara Core](https://github.com/weberq/taracore) (`dev.taracore:client-sdk`) | Apache-2.0 |
| AndroidX, Jetpack Compose, Material 3 | Apache-2.0 |
| Google AI Edge `aicore` | Google APIs Terms of Service |

Sentry itself sends nothing off the device. The only network access in the project is
`scripts/fetch-models.sh`, which is a developer tool and not part of the app.
