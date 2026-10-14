"""Held-out positives in context: [sentence][gap]["hey grok" | "hey grok bot"][1.5 s tail], noise added.
Saves sliding 16x96 windows + per-utterance frame ranges so a decider can be simulated end-to-end."""
import numpy as np, random, json
from gen import tts, noise, feats, SR, VOICES; from corpus import sentences, POS
rng = np.random.default_rng(77); r = random.Random(77); F = feats()
for target in ["hey_grok", "hey_grok_bot"]:
    W_all, ranges, off = [], [], 0
    for i in range(300):
        v = r.choice(list(VOICES)); n = 904 if v == "libritts_r" else 109; spk = r.randrange(int(n * .85), n)
        ctx = tts(" ".join(sentences(1, r.random())), v, spk, rng); kw = tts(r.choice(POS[target]), v, spk, rng)
        g = lambda a, b: np.zeros(int(SR * rng.uniform(a, b)), np.float32)
        parts = [g(0.3, 0.8), ctx, g(0.3, 1.0)]; kstart = sum(map(len, parts)); parts += [kw, g(1.5, 1.6)]
        a = np.concatenate(parts); a = a / (np.abs(a).max() + 1e-9) * rng.uniform(0.1, 0.8)
        a += noise(len(a), rng) * rng.uniform(0.002, 0.03)
        E = F._get_embeddings((a.clip(-1, 1) * 32767).astype(np.int16))
        W = np.lib.stride_tricks.sliding_window_view(E, (16, 96))[:, 0]
        # window j ends at frame j+15 -> sample (j+15+1)*1280 + 76*160 approx; keyword ends at kstart+len(kw)
        kend_frame = (kstart + len(kw)) // 1280
        ranges.append([off, off + len(W), kend_frame - 16 - 4, len(W)]); W_all.append(W); off += len(W)
    np.save(f"features/posstream_{target}.npy", np.concatenate(W_all).astype(np.float32))
    json.dump(ranges, open(f"features/posstream_{target}.json", "w")); print(target, off, flush=True)
