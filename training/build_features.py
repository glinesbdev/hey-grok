import sys, random; from gen import run; from corpus import *
split = sys.argv[1]; k = 1 if split == "train" else 0.25; r = random.Random(1)
for name, ph in POS.items():
    run(f"{split}_pos_{name}", [r.choice(ph) for _ in range(int(2500 * k))], True, 2, split, seed=len(name) + (0 if split == "train" else 500))
run(f"{split}_neg_conf", [r.choice(CONF) for _ in range(int(3000 * k))], True, 2, split, seed=7 + (0 if split == "train" else 500))
run(f"{split}_neg_speech", sentences(int(6000 * k), 3 if split == "train" else 503), False, 1, split, seed=9 + (0 if split == "train" else 500))
run(f"{split}_neg_noise", ["<noise>"] * int(1500 * k), False, 1, split, seed=11 + (0 if split == "train" else 500))
