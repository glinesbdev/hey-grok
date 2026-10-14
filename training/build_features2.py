# Extra negatives with random (not end-aligned) placement, so models learn timing/context.
import sys, random; from gen import run; from corpus import *
split = sys.argv[1]; k = 1 if split == "train" else 0.25; r = random.Random(2); off = 0 if split == "train" else 500
run(f"{split}_neg_conf_rand", [r.choice(CONF) for _ in range(int(3000 * k))], False, 2, split, seed=21 + off)
run(f"{split}_neg_grok_rand", [r.choice(POS["hey_grok"]) for _ in range(int(2000 * k))], False, 2, split, seed=23 + off)
