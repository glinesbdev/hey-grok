"""Held-out metrics for v2. Streams: 'stream' (1 h, 15 % confusables) and 'stream_speech' (0.5 h plain speech),
both from held-out speakers. 'combined' = app-level false accepts (either model fires, 1 s refractory)."""
import numpy as np, onnxruntime as ort, json
L = lambda n: np.load(f"features/{n}.npy")
S = {k: ort.InferenceSession(f"out/{k}.onnx") for k in ["hey_grok", "hey_grok_bot"]}
sc = lambda k, X: S[k].run(None, {"input": X.astype(np.float32)})[0][:, 0]
def fa_per_hour(s, th, hours):
    n, last = 0, -99
    for i in np.flatnonzero(s >= th):
        if i - last > 12: n += 1
        last = i
    return n / hours
streams = {n: (L(n), float(open(f"features/{n}_hours.txt").read())) for n in ["stream", "stream_speech"]}
ss = {(k, n): sc(k, X) for k in S for n, (X, _) in streams.items()}
res = {}
for t in S:
    pos = sc(t, L(f"test_pos_{t}"))
    g = {k: sc(t, L(f"test_neg_{k}")) for k in ["conf", "conf_rand", "speech", "noise"]}
    if t == "hey_grok_bot": g["hey_grok_alone"] = sc(t, L("test_pos_hey_grok")); g["hey_grok_alone_rand"] = sc(t, L("test_neg_grok_rand"))
    res[t] = {}
    for th in [0.3, 0.5, 0.7, 0.8, 0.9, 0.95, 0.98]:
        d = {"recall": float((pos >= th).mean())}
        d.update({f"fa_rate_{k}": float((v >= th).mean()) for k, v in g.items()})
        d.update({f"fa_per_hour_{n}": fa_per_hour(ss[(t, n)], th, h) for n, (_, h) in streams.items()})
        res[t][str(th)] = d
res["combined"] = {str(th): {f"fa_per_hour_{n}": fa_per_hour(np.maximum(ss[("hey_grok", n)], ss[("hey_grok_bot", n)]), th, h)
                              for n, (_, h) in streams.items()} for th in [0.5, 0.7, 0.8, 0.9, 0.95, 0.98]}
json.dump(res, open("out/metrics.json", "w"), indent=1)
for t, v in res.items():
    print(t)
    for th, m in v.items(): print(" ", th, {k.replace("fa_rate_", "").replace("fa_per_hour_", "h_"): round(x, 4) for k, x in m.items()})
