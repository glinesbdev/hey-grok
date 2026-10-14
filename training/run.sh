#!/usr/bin/env bash
# Retrain the "hey grok" / "hey grok bot" openWakeWord models (v4) from scratch on CPU.
# Every step skips work whose output already exists, so it can be rerun after an interruption.
# Roughly 1.5–2 h on 8 cores; most of it is generating synthetic audio.
set -uo pipefail
cd "$(dirname "$0")"; mkdir -p logs models voices features out
log(){ echo "$(date +%T) $*" | tee -a logs/run.log; }
[ -x venv/bin/python ] || { python3 -m venv venv && venv/bin/pip install -r requirements.txt; }
PY=venv/bin/python
R=https://github.com/dscripka/openWakeWord/releases/download/v0.5.1
for f in melspectrogram.onnx embedding_model.onnx; do [ -f models/$f ] || curl -fL "$R/$f" -o "models/$f"; done
H=https://huggingface.co/rhasspy/piper-voices/resolve/main/en
for v in en_US/libritts_r/medium/en_US-libritts_r-medium en_GB/vctk/medium/en_GB-vctk-medium; do
  n=$(basename "$v"); [ -f voices/$n.onnx ] || { curl -fL "$H/$v.onnx" -o "voices/$n.onnx"; curl -fL "$H/$v.onnx.json" -o "voices/$n.onnx.json"; }
done
have(){ for f in "$@"; do [ -f "features/$f.npy" ] || return 1; done; }
for s in train test; do
  have ${s}_pos_hey_grok ${s}_pos_hey_grok_bot ${s}_neg_conf ${s}_neg_speech ${s}_neg_noise || { log "clip features $s"; $PY build_features.py $s >> logs/bf1_$s.log 2>&1; }
  have ${s}_neg_conf_rand ${s}_neg_grok_rand || { log "random-placement negatives $s"; $PY build_features2.py $s >> logs/bf2_$s.log 2>&1; }
done
# Held-out evaluation streams (held-out speakers)
have stream || { log "eval stream (1 h, confusables)"; $PY stream.py >> logs/stream.log 2>&1; }
have stream_speech || { log "eval stream (0.5 h, plain speech)"; $PY stream_speech.py >> logs/stream_speech.log 2>&1; }
[ -f features/posstream_hey_grok_bot.npy ] || { log "eval positives in context"; $PY pos_stream.py >> logs/pos_stream.log 2>&1; }
# Training hard negatives: 4 x 1 h continuous audio (training speakers), run in parallel
for k in 2 3 4 5; do have train_stream$k || { log "train stream $k"; $PY stream_train$k.py >> logs/stream_train$k.log 2>&1 & }; done; wait
# Training positives in context
for t in hey_grok hey_grok_bot; do have train_posctx_$t || { log "in-context positives $t"; $PY pos_stream_train.py $t >> logs/posctx_$t.log 2>&1 & }; done; wait
log "train v4"; $PY train_v4.py >> logs/train_v4.log 2>&1
log "evaluate"; $PY eval_v2.py | tee logs/eval.txt; $PY decider_eval.py | tee logs/decider.txt
cp out/hey_grok.onnx out/hey_grok_bot.onnx ../app/src/main/assets/oww/ && log "models copied to app assets"
