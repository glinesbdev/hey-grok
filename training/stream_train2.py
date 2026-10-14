"""~1 h continuous negative audio (held-out speakers, random sentences + confusables + noise) → sliding 16-frame windows."""
import numpy as np, random
from gen import tts, noise, feats, SR; from corpus import sentences, CONF
rng = np.random.default_rng(46); r = random.Random(46); parts = []; total = 0
from gen import VOICES
while total < 3600 * SR:
    v = r.choice(list(VOICES)); n = 904 if v == "libritts_r" else 109
    t = r.choice(CONF) if r.random() < 0.15 else " ".join(sentences(3, r.random())); a = tts(t, v, r.randrange(0, int(n * .85)), rng)
    a = a / (np.abs(a).max() + 1e-9) * rng.uniform(0.1, 0.8)
    gap = np.zeros(int(SR * rng.uniform(0.2, 1.5)), np.float32); seg = np.concatenate([a, gap])
    seg += noise(len(seg), rng) * rng.uniform(0.002, 0.03); parts.append(seg); total += len(seg)
audio = (np.concatenate(parts).clip(-1, 1) * 32767).astype(np.int16)
F = feats()
emb = []
step = 60 * SR
for i in range(0, len(audio), step):  # chunks with overlap so windows are continuous enough
    e = F._get_embeddings(audio[i:i + step + 2 * SR])
    emb.append(e[: (step // 1280)] if i + step < len(audio) else e)
E = np.concatenate(emb); W = np.lib.stride_tricks.sliding_window_view(E, (16, 96))[:, 0]
np.save("features/train_stream2.npy", W.astype(np.float32)); open("features/train_stream2_hours.txt", "w").write(str(len(audio) / SR / 3600))
print("stream", W.shape, len(audio) / SR / 3600)
