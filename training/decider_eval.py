"""End-to-end app-level metrics: simulate WakeDecider (thresholds + patience + bot wait) over
held-out positive-in-context streams (recall / confusion) and negative streams (false accepts/hour)."""
import numpy as np, onnxruntime as ort, json, itertools, sys
S = {k: ort.InferenceSession(f"out/{k}.onnx") for k in ["hey_grok", "hey_grok_bot"]}
sc = lambda X: (S["hey_grok"].run(None, {"input": X})[0][:, 0], S["hey_grok_bot"].run(None, {"input": X})[0][:, 0])
def decide(g, b, tg, tb, pg, pb, wait=8, refractory=12):
    """Mirror of WakeDecider.kt. Returns list of (frame, 'G'|'B')."""
    ev, cg, cb, pending, cool = [], 0, 0, -1, 0
    for i in range(len(g)):
        if cool > 0: cool -= 1; cg = cb = 0; continue
        cg = cg + 1 if g[i] >= tg else 0
        cb = cb + 1 if b[i] >= tb else 0
        if cb >= pb: ev.append((i, "B")); pending = -1; cool = refractory; continue
        if pending >= 0:
            pending += 1
            if pending >= wait: ev.append((i, "G")); pending = -1; cool = refractory
        elif cg >= pg: pending = 0
    return ev
def run(cfg):
    tg, tb, pg, pb = cfg; out = {}
    for t, lab in [("hey_grok", "G"), ("hey_grok_bot", "B")]:
        X = np.load(f"features/posstream_{t}.npy"); R = json.load(open(f"features/posstream_{t}.json")); g, b = sc(X)
        ok = wrong = early = 0
        for s, e, kend, _ in R:
            ev = decide(g[s:e], b[s:e], tg, tb, pg, pb)
            pre = [x for x in ev if x[0] < kend - 15]; post = [x for x in ev if x[0] >= kend - 15]
            early += bool(pre)
            if post and post[0][1] == lab: ok += 1
            elif post: wrong += 1
        out[t] = {"recall": ok / len(R), "wrong_phrase": wrong / len(R), "fa_in_context_sentence": early / len(R)}
    for n in ["stream", "stream_speech"]:
        X = np.load(f"features/{n}.npy"); h = float(open(f"features/{n}_hours.txt").read()); g, b = sc(X)
        out[f"fa_per_hour_{n}"] = len(decide(g, b, tg, tb, pg, pb)) / h
    return out
if __name__ == "__main__":
    grid = list(itertools.product([0.5, 0.7, 0.8, 0.9], [0.5, 0.8, 0.9], [1, 2, 3, 4], [1, 2, 3, 4]))
    res = {}
    for cfg in grid:
        res[str(cfg)] = r = run(cfg)
    json.dump(res, open("out/decider_grid.json", "w"), indent=1)
    good = [(k, v) for k, v in res.items() if v["hey_grok"]["recall"] >= 0.9 and v["hey_grok_bot"]["recall"] >= 0.9]
    good.sort(key=lambda kv: (max(kv[1]["fa_per_hour_stream"], kv[1]["fa_per_hour_stream_speech"]), -kv[1]["hey_grok"]["recall"]))
    for k, v in good[:10]: print(k, json.dumps({a: (round(b, 3) if isinstance(b, float) else {x: round(y, 3) for x, y in b.items()}) for a, b in v.items()}))
    print("configs meeting recall>=0.9:", len(good))
