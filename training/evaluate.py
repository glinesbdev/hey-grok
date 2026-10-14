"""Recall on held-out speakers + false accepts on held-out negatives and a continuous synthetic negative stream."""
import numpy as np, onnxruntime as ort, json, sys
L = lambda n: np.load(f"features/{n}.npy")
def score(path, X):
    s = ort.InferenceSession(path); return s.run(None, {"input": X.astype(np.float32)})[0][:, 0]
res = {}
import os
speech_streams = [(np.load("features/stream_speech.npy"), float(open("features/stream_speech_hours.txt").read()),
                   "false_accepts_per_hour_plain_speech")] if os.path.exists("features/stream_speech.npy") else []
stream = np.load("features/stream.npy") if len(sys.argv) > 1 else None
hours = float(open("features/stream_hours.txt").read()) if stream is not None else 0
for t in ["hey_grok", "hey_grok_bot"]:
    p = f"out/{t}.onnx"; r = {}
    pos = score(p, L(f"test_pos_{t}"))
    groups = {k: score(p, L(f"test_neg_{k}")) for k in ["conf", "conf_rand", "speech", "noise"]}
    if t == "hey_grok_bot": groups["hey_grok_alone"] = score(p, L("test_pos_hey_grok")); groups["hey_grok_alone_rand"] = score(p, L("test_neg_grok_rand"))
    if stream is not None: ss = score(p, stream)
    for th in [0.3, 0.5, 0.7, 0.9]:
        d = {"recall": float((pos >= th).mean())}
        for k, v in groups.items(): d[f"fa_rate_{k}"] = float((v >= th).mean())
        if stream is not None:  # count activations with 1 s (12 frame) refractory period
            n, last = 0, -99
            for i, v in enumerate(ss):
                if v >= th and i - last > 12: n += 1; last = i
            d["false_accepts_per_hour_stream"] = n / hours
        for sp, sh, key in speech_streams:
            sv = score(p, sp); n, last = 0, -99
            for i, v in enumerate(sv):
                if v >= th and i - last > 12: n += 1; last = i
            d[key] = n / sh
        r[str(th)] = d
    r["n_pos_test"] = len(pos); r.update({f"n_{k}": len(v) for k, v in groups.items()}); res[t] = r
json.dump(res, open("out/metrics.json", "w"), indent=1); print(json.dumps(res, indent=1))
