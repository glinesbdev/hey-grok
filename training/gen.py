"""Synthesize + augment + featurize clips for openWakeWord-style training.
Outputs features/<name>.npy with shape (N, 16, 96) float32. Only permissively
licensed voices (LibriTTS-R CC BY 4.0, VCTK CC BY 4.0) are used."""
import numpy as np, random, sys, os, json
from multiprocessing import Pool
from scipy.signal import resample_poly, fftconvolve

SR = 16000; WIN = 2 * SR
VOICES = {"libritts_r": "voices/en_US-libritts_r-medium.onnx", "vctk": "voices/en_GB-vctk-medium.onnx"}
_v = {}; _feat = None

def voice(name):
    from piper import PiperVoice
    if name not in _v: _v[name] = PiperVoice.load(VOICES[name])
    return _v[name]

def feats():
    global _feat
    if _feat is None:
        from openwakeword.utils import AudioFeatures
        _feat = AudioFeatures("models/melspectrogram.onnx", "models/embedding_model.onnx")
    return _feat

def tts(text, vname, spk, rng):
    from piper import SynthesisConfig
    v = voice(vname)
    cfg = SynthesisConfig(speaker_id=spk, length_scale=rng.uniform(0.75, 1.35),
                          noise_scale=rng.uniform(0.4, 0.9), noise_w_scale=rng.uniform(0.5, 1.1))
    a = np.frombuffer(b"".join(c.audio_int16_bytes for c in v.synthesize(text, cfg)), np.int16).astype(np.float32) / 32768
    a = resample_poly(a, 160, 220.5 * 1).astype(np.float32) if False else resample_poly(a, 320, 441).astype(np.float32)
    return a

def noise(n, rng):
    kind = rng.choice(["white", "pink", "brown", "hum", "babble0"])
    w = rng.standard_normal(n).astype(np.float32)
    if kind == "pink":
        f = np.fft.rfft(w); f /= np.sqrt(np.arange(1, len(f) + 1)); w = np.fft.irfft(f, n)
    elif kind == "brown":
        w = np.cumsum(w); w -= np.convolve(w, np.ones(400) / 400, "same")
    elif kind == "hum":
        t = np.arange(n) / SR; w = sum(np.sin(2 * np.pi * rng.uniform(50, 400) * k * t) / k for k in range(1, 4)) + 0.1 * w
    return (w / (np.std(w) + 1e-9)).astype(np.float32)

def augment(a, rng, end_align=True):
    a = a.copy()
    if rng.random() < 0.4:  # synthetic room reverb
        L = int(SR * rng.uniform(0.05, 0.4)); ir = rng.standard_normal(L) * np.exp(-np.arange(L) / (L / rng.uniform(3, 8)))
        ir[0] = 1; a = fftconvolve(a, ir)[: len(a)]; 
    a = a[-WIN:]
    out = np.zeros(WIN, np.float32)
    if end_align:  # wake word ends near the end of the 2 s window (as openWakeWord does)
        end = WIN - int(rng.uniform(0, 0.35) * SR); out[max(0, end - len(a)):end] = a[-end:]
    else:
        s = rng.integers(0, max(1, WIN - len(a))); out[s:s + len(a)] = a[: WIN - s]
    sig = np.std(out[out != 0]) + 1e-9
    snr = rng.uniform(5, 30); out += noise(WIN, rng) * sig / (10 ** (snr / 20)) * (rng.random() < 0.85)
    out *= rng.uniform(0.1, 0.9) / (np.abs(out).max() + 1e-9)
    return (out * 32767).astype(np.int16)

def job(args):
    texts, vname, spks, seed, end_align, reps = args
    rng = np.random.default_rng(seed); clips = []
    for t, s in zip(texts, spks):
        if t == "<noise>":
            clips.append((noise(WIN, rng) * rng.uniform(0.01, 0.3) * 32767).clip(-32767, 32767).astype(np.int16)); continue
        a = tts(t, vname, s, rng)
        for _ in range(reps): clips.append(augment(a, rng, end_align))
    X = np.stack(clips)
    return feats().embed_clips(X, batch_size=64)[:, -16:, :].astype(np.float32)

def run(name, texts, end_align, reps, speaker_split, seed=0):
    rng = random.Random(seed); jobs = []
    vn = list(VOICES)
    for i in range(0, len(texts), 50):
        chunk = texts[i:i + 50]; v = rng.choice(vn)
        nspk = 904 if v == "libritts_r" else 109
        lo, hi = (0, int(nspk * 0.85)) if speaker_split == "train" else (int(nspk * 0.85), nspk)
        jobs.append((chunk, v, [rng.randrange(lo, hi) for _ in chunk], seed * 100000 + i, end_align, reps))
    with Pool(8) as p: out = p.map(job, jobs)
    F = np.concatenate(out); os.makedirs("features", exist_ok=True); np.save(f"features/{name}.npy", F)
    print(name, F.shape, flush=True)
