#!/usr/bin/env bash
# Retrain the "hey grok" / "hey grok bot" openWakeWord models from scratch (CPU, ~45 min on 8 cores).
set -euo pipefail
cd "$(dirname "$0")"
python3 -m venv venv && . venv/bin/activate && pip install -r requirements.txt
mkdir -p models voices
R=https://github.com/dscripka/openWakeWord/releases/download/v0.5.1
for f in melspectrogram.onnx embedding_model.onnx; do curl -fL "$R/$f" -o "models/$f"; done
H=https://huggingface.co/rhasspy/piper-voices/resolve/main/en
for v in en_US/libritts_r/medium/en_US-libritts_r-medium en_GB/vctk/medium/en_GB-vctk-medium; do
  n=$(basename "$v"); curl -fL "$H/$v.onnx" -o "voices/$n.onnx"; curl -fL "$H/$v.onnx.json" -o "voices/$n.onnx.json"
done
python build_features.py train && python build_features.py test
python build_features2.py train && python build_features2.py test
python stream.py && python stream_speech.py
# Optional hard negatives (not used for the committed v1 models): python stream_train.py
python train.py && python evaluate.py stream
cp out/hey_grok.onnx out/hey_grok_bot.onnx ../app/src/main/assets/oww/
