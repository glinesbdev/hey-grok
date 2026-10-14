# Wake-word models

| File (app/src/main/assets/oww/) | Size | Source | License |
|---|---|---|---|
| `melspectrogram.onnx` | 1,087,958 B (1.04 MiB) | openWakeWord v0.5.1 release | see caveat below |
| `embedding_model.onnx` | 1,326,578 B (1.27 MiB) | openWakeWord v0.5.1 release (re-implementation of Google `speech_embedding`, Apache-2.0) | see caveat below |
| `hey_grok.onnx` | 858,044 B (0.82 MiB) | trained here | trained on CC BY 4.0-derived data |
| `hey_grok_bot.onnx` | 858,044 B (0.82 MiB) | trained here | trained on CC BY 4.0-derived data |

Only ONNX is shipped. TFLite export was skipped because onnx2tf/TensorFlow is a heavy dependency, and ONNX Runtime Mobile runs the ONNX models directly.

## Model

These are openWakeWord-style classifiers. Input is `[batch, 16, 96]`, the last 16 embeddings ≈ 1.28 s of audio.
Output is `[batch, 1]`, a sigmoid score.
Layers: `Flatten → Linear(1536,128) → LayerNorm → ReLU → Linear(128,128) → LayerNorm → ReLU → Linear(128,1) → Sigmoid`, exported with ONNX opset 13.
In the app, `OwwPipeline` streams 80 ms frames. `WakeDecider` applies the threshold (default 0.5, configurable in `OpenWakeWordEngine`) and waits about 640 ms after "hey grok" fires to see whether "hey grok bot" also fires.

## Training data (100 % synthetic, generated on the fly, not committed)

- **TTS:** [Piper](https://github.com/OHF-Voice/piper1-gpl) (`piper-tts` 1.8.0, GPL-3.0; used only as a tool, never shipped) with:
  - `en_US-libritts_r-medium`, 904 speakers, trained on [LibriTTS-R](http://www.openslr.org/141/) (CC BY 4.0, Koizumi et al. 2023)
  - `en_GB-vctk-medium`, 109 speakers, trained on [VCTK](https://datashare.ed.ac.uk/handle/10283/3443) (CC BY 4.0, CSTR, University of Edinburgh)
  - `en_US-l2arctic-medium` was **deliberately excluded** because its dataset is CC BY-NC 4.0.
- **Speakers:** the first 85 % of speaker IDs are for training and the last 15 % are **held out** for every test number below.
- **Text:**
  - Positives: "hey grok" / "hey grok bot" with punctuation and spelling variants.
  - Negatives: 34 confusable phrases ("hey google", "hey rock", "hey grog", "a grok", "grok bot", …), random word sentences, and synthetic noise.
  - The bot model also learns "hey grok" alone as a negative.
- **Augmentation** (no external noise or RIR datasets, so there are no extra licenses):
  - Piper length, noise and noise_w randomization
  - synthetic exponential-decay reverb (40 %)
  - white, pink, brown, hum noise at 5–30 dB SNR
  - random gain
  - positives end 0–0.35 s before the end of a 2 s window; negatives are both end-aligned and randomly placed
- **Train set:** 5,000 positives per phrase, plus negatives:
  - 6,000 confusable (end-aligned)
  - 6,000 confusable (random placement)
  - 6,000 sentences
  - 1,500 noise
  - bot model only: an extra 9,000 "hey grok" clips
- **Training:** 60 epochs, AdamW lr 1e-3, weight decay 1e-2, batch 256, BCE with 3× weight on negatives. About 5 minutes on 8 CPU cores.

## Metrics (held-out speakers, synthetic audio)

Full numbers are in `training/metrics.json`. At the default threshold of **0.5**:

| | hey_grok | hey_grok_bot |
|---|---|---|
| Recall (1,250 held-out positives) | **95.3 %** | **98.8 %** |
| False accepts, confusable clips (end-aligned / random placement) | 6.5 % / 1.7 % | 0.07 % / 0.13 % |
| False accepts, random-sentence clips | 0.27 % | 0.13 % |
| False accepts, noise clips | 0 % | 0 % |
| Bot model fires on "hey grok" alone (end-aligned / random) | n/a | 0 % / 0.1 % |
| **False accepts/hour, 1 h continuous synthetic stream** (15 % confusables) | **66** | **86** |
| **False accepts/hour, 0.5 h continuous plain-speech stream** | **78** | **80** |

At threshold 0.9: hey_grok has 93.0 % recall and 44 FA/h, hey_grok_bot 98.4 % and 58 FA/h.

**The false-accept rate on continuous speech is far too high for real use.** These v1 models only saw isolated 2 s clips, so the windows that straddle continuous speech are new to them.
Next step: train with hard negatives from long continuous negative audio. The script is `training/stream_train.py`, and `train.py` picks the output up automatically. Adding a permissively licensed real-speech negative corpus would also help.
All numbers come from synthetic TTS. Real microphones, accents and rooms will differ, and nothing has been measured on a device yet.

## Licenses and redistribution

- **Our keyword models** were trained only on audio generated from voices whose data is CC BY 4.0, which allows redistributing derived works with attribution (given above).
  **Flag:** Piper's `libritts_r` and `vctk` voices were fine-tuned from Piper's `lessac` voice. The Lessac/Blizzard 2011 data behind it has non-commercial terms.
  Whether that carries through synthetic samples into a classifier is legally unclear. Review it before any commercial use.
- **melspectrogram.onnx / embedding_model.onnx:** the openWakeWord code is Apache-2.0, but its README says *"all of the included pre-trained models"* are CC BY-NC-SA 4.0.
  The embedding model is a re-implementation of Google's Apache-2.0 `speech_embedding`, and the mel model is a fixed DSP transform, yet upstream doesn't explicitly exempt either one.
  **Flag:** treat both as **possibly CC BY-NC-SA 4.0 (non-commercial, share-alike)** until upstream confirms otherwise. That's fine for this personal open-source project, but check before any commercial release.
- ONNX Runtime: MIT. Test clips in `app/src/test/resources/clips` were generated with the same CC BY 4.0-derived voices.

## Retrain

```bash
cd training && ./run.sh   # creates a venv, downloads voices and feature models, generates data, trains, evaluates, and copies models to assets
```

The generated datasets (`training/features`, `voices`, `models`, `out`, `*.npy`) are gitignored and never committed.
