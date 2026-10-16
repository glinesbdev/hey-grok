# Third-party notices

Hey Grok's own code is under the PolyForm Noncommercial License 1.0.0 (see `LICENSE`).
The components below are **not** covered by that license. Each keeps its own license.

| Component | Where | License | Notes |
|---|---|---|---|
| openWakeWord `melspectrogram.onnx`, `embedding_model.onnx` (v0.5.1) | `app/src/main/assets/oww/` | **CC BY-NC-SA 4.0** (openWakeWord pre-trained models). Code is Apache-2.0 | © David Scripka, https://github.com/dscripka/openWakeWord. The embedding model re-implements Google's `speech_embedding` (Apache-2.0). Non-commercial, share-alike. |
| `hey_grok.onnx`, `hey_grok_bot.onnx` | `app/src/main/assets/oww/` | Trained in this repo on CC BY 4.0-derived synthetic audio | See "Voice data" below and MODELS.md (caveat about Piper's lessac base voice). |
| Test clips | `app/src/test/resources/clips/` | Synthetic, CC BY 4.0-derived | Same voices as below. |
| ONNX Runtime (`onnxruntime-android`, `onnxruntime`) | Gradle dependency | MIT | © Microsoft Corporation |
| OkHttp | Gradle dependency | Apache-2.0 | © Square, Inc. |
| AndroidX / Jetpack Compose / security-crypto / Browser (Custom Tabs) | Gradle dependencies | Apache-2.0 | © The Android Open Source Project |
| Kotlin, kotlinx.coroutines | Gradle dependencies | Apache-2.0 | © JetBrains |
| Piper TTS (`piper-tts`) | training tool only, not shipped | GPL-3.0 | Used only to generate training audio. |

## Voice data (attribution, CC BY 4.0)

The training and test audio was synthesized with Piper voices trained on:

- **LibriTTS-R**, Y. Koizumi et al., 2023, http://www.openslr.org/141/, licensed CC BY 4.0
  (derived from LibriTTS / LibriSpeech, public-domain LibriVox recordings).
- **VCTK Corpus 0.92**, J. Yamagishi, C. Veaux, K. MacDonald, CSTR, University of Edinburgh,
  https://datashare.ed.ac.uk/handle/10283/3443, licensed CC BY 4.0.

License texts: https://creativecommons.org/licenses/by/4.0/, https://creativecommons.org/licenses/by-nc-sa/4.0/,
https://opensource.org/license/mit, https://www.apache.org/licenses/LICENSE-2.0.
