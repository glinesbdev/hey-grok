"""Training-speaker positives in context. Saves windows whose end is 0-0.4 s after the keyword ends
(matching the end-aligned clip positives) as extra positives: features/train_posctx_<target>.npy"""
import numpy as np, random, sys
from gen import tts, noise, feats, SR, VOICES; from corpus import sentences, POS
target = sys.argv[1]; seed = 900 + len(target)
import os
F = feats(); CK = f"features/ckpt_posctx_{target}"; os.makedirs(CK, exist_ok=True)
for c in range(10):                                       # 10 resumable chunks of 100 utterances
    if os.path.exists(f"{CK}/{c}.npy"): continue
    rng = np.random.default_rng(seed * 100 + c); r = random.Random(seed * 100 + c); out = []
    for i in range(100):
        v = r.choice(list(VOICES)); n = 904 if v == "libritts_r" else 109; spk = r.randrange(0, int(n * .85))
        ctx = tts(" ".join(sentences(1, r.random())), v, spk, rng); kw = tts(r.choice(POS[target]), v, spk, rng)
        g = lambda a, b: np.zeros(int(SR * rng.uniform(a, b)), np.float32)
        parts = [g(0.3, 0.8), ctx, g(0.05, 1.0)]; kstart = sum(map(len, parts)); parts += [kw, g(1.0, 1.1)]
        a = np.concatenate(parts); a = a / (np.abs(a).max() + 1e-9) * rng.uniform(0.1, 0.8)
        a += noise(len(a), rng) * rng.uniform(0.002, 0.05)
        E = F._get_embeddings((a.clip(-1, 1) * 32767).astype(np.int16))
        W = np.lib.stride_tricks.sliding_window_view(E, (16, 96))[:, 0]
        j = int((kstart + len(kw)) / 1280 - 24.5)          # window whose end ~= keyword end
        sel = W[max(0, j): j + 5]                           # keyword ends 0-0.4 s before window end
        out.append(sel)
    np.save(f"{CK}/{c}.npy", np.concatenate(out).astype(np.float32)); print("chunk", c, flush=True)
np.save(f"features/train_posctx_{target}.npy", np.concatenate([np.load(f"{CK}/{c}.npy") for c in range(10)])); print("done", flush=True)
