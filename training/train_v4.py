"""v2: clip negatives + continuous-stream windows (training speakers only) with hard-negative mining.
Pass 1 trains on everything; pass 2 upweights stream windows that pass 1 scored >= 0.1."""
import numpy as np, torch, torch.nn as nn, glob, os, sys, json, time
torch.manual_seed(0); np.random.seed(0); torch.set_num_threads(8)
L = lambda n: np.load(f"features/{n}.npy", mmap_mode="r")
def log(*a): print(time.strftime("%T"), *a, flush=True)
def model():
    return nn.Sequential(nn.Flatten(), nn.Linear(16 * 96, 128), nn.LayerNorm(128), nn.ReLU(), nn.Dropout(0.3),
                         nn.Linear(128, 128), nn.LayerNorm(128), nn.ReLU(), nn.Dropout(0.3), nn.Linear(128, 1), nn.Sigmoid())
def fit(X, y, w, epochs):
    m = model(); opt = torch.optim.AdamW(m.parameters(), 1e-3, weight_decay=1e-2)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, epochs)
    for ep in range(epochs):
        perm = torch.randperm(len(X)); m.train()
        for i in range(0, len(X), 512):
            b = perm[i:i + 512]
            loss = nn.functional.binary_cross_entropy(m(X[b]).squeeze(1), y[b], weight=w[b])
            opt.zero_grad(); loss.backward(); opt.step()
        sched.step()
    return m.eval()
def train(target):
    other = "hey_grok_bot" if target == "hey_grok" else "hey_grok"
    pos = np.asarray(L(f"train_pos_{target}"))
    if os.path.exists(f"features/train_posctx_{target}.npy"):  # in-context positives (training speakers)
        pos = np.concatenate([pos, np.asarray(L(f"train_posctx_{target}"))]); log(target, "positives incl. in-context", pos.shape)
    clip_negs = [L("train_neg_conf"), L("train_neg_conf_rand"), L("train_neg_speech"), L("train_neg_noise")]
    if target == "hey_grok_bot": clip_negs += [L(f"train_pos_{other}"), L("train_neg_grok_rand")]
    clip_neg = np.concatenate([np.asarray(a) for a in clip_negs])
    streams = sorted(f[9:-4] for f in glob.glob("features/train_stream*.npy"))
    stream = np.concatenate([np.asarray(L(s)) for s in streams]); log(target, "streams", streams, stream.shape)
    X = torch.tensor(np.concatenate([pos, clip_neg, stream]))
    y = torch.tensor(np.r_[np.ones(len(pos)), np.zeros(len(clip_neg) + len(stream))], dtype=torch.float32)
    w = torch.tensor(np.r_[np.ones(len(pos)), np.full(len(clip_neg), 3.0), np.full(len(stream), 1.0)], dtype=torch.float32)
    m = fit(X, y, w, 25); log(target, "pass1 done")
    with torch.no_grad(): s = m(torch.tensor(stream)).squeeze(1).numpy()
    hard = s >= 0.1; log(target, "hard stream windows", int(hard.sum()))
    w[len(pos) + len(clip_neg):][torch.tensor(hard)] = 20.0
    m = fit(X, y, w, 40); log(target, "pass2 done")
    os.makedirs("out", exist_ok=True)
    torch.onnx.export(m, torch.zeros(1, 16, 96), f"out/{target}.onnx", input_names=["input"], output_names=["output"],
                      dynamic_axes={"input": {0: "batch"}, "output": {0: "batch"}}, opset_version=13, dynamo=False)
if __name__ == "__main__":
    for t in (sys.argv[1:] or ["hey_grok", "hey_grok_bot"]): train(t)
