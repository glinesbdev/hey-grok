import numpy as np, torch, torch.nn as nn, sys, json
torch.manual_seed(0); np.random.seed(0)
L = lambda n: np.load(f"features/{n}.npy")
def model():
    return nn.Sequential(nn.Flatten(), nn.Linear(16 * 96, 128), nn.LayerNorm(128), nn.ReLU(),
                         nn.Linear(128, 128), nn.LayerNorm(128), nn.ReLU(), nn.Linear(128, 1), nn.Sigmoid())
def train(target):
    other = "hey_grok_bot" if target == "hey_grok" else "hey_grok"
    pos = L(f"train_pos_{target}")
    negs = [L("train_neg_conf"), L("train_neg_conf_rand"), L("train_neg_speech"), L("train_neg_noise")]
    if target == "hey_grok_bot": negs += [L(f"train_pos_{other}"), L("train_neg_grok_rand")]  # "hey grok" alone must not fire the bot model
    import os
    if os.path.exists("features/train_stream.npy"):  # optional hard negatives (python stream_train.py); NOT used for the committed v1 models
        negs.append(L("train_stream"))
    neg = np.concatenate(negs)
    X = torch.tensor(np.concatenate([pos, neg])); y = torch.tensor(np.r_[np.ones(len(pos)), np.zeros(len(neg))], dtype=torch.float32)
    m = model(); opt = torch.optim.AdamW(m.parameters(), 1e-3, weight_decay=1e-2)
    w_neg = 3.0  # penalize false accepts more than misses
    for ep in range(60):
        perm = torch.randperm(len(X))
        for i in range(0, len(X), 256):
            b = perm[i:i + 256]; p = m(X[b]).squeeze(1)
            wt = torch.where(y[b] == 1, 1.0, w_neg)
            loss = nn.functional.binary_cross_entropy(p, y[b], weight=wt)
            opt.zero_grad(); loss.backward(); opt.step()
    m.eval()
    torch.onnx.export(m, torch.zeros(1, 16, 96), f"out/{target}.onnx", input_names=["input"], output_names=["output"],
                      dynamic_axes={"input": {0: "batch"}, "output": {0: "batch"}}, opset_version=13, dynamo=False)
    return m
if __name__ == "__main__":
    import os; os.makedirs("out", exist_ok=True)
    for t in ["hey_grok", "hey_grok_bot"]: train(t); print("trained", t, flush=True)
