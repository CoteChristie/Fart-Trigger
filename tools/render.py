import numpy as np, scipy.io.wavfile as wv, sys, compufart as c
out = sys.argv[1]
V = {
 "fart_classic":  dict(seed=1, base_hz=80,  dur=0.55),
 "fart_quick":    dict(seed=2, base_hz=120, dur=0.12, rel=0.25),
 "fart_long":     dict(seed=3, base_hz=62,  dur=1.5, rel=1.0, sputter=2, wobble=0.35),
 "fart_squeaker": dict(seed=4, base_hz=210, dur=0.5, pinch=0.85, cheek=0.3, pitch_oct=1.0, wobble=0.2),
 "fart_sputter":  dict(seed=5, base_hz=72,  dur=1.0, sputter=8, wobble=0.2),
 "fart_wet":      dict(seed=6, base_hz=55,  dur=0.7, strain=1.0, intensity=0.9, cheek=0.9),
 "fart_rumbler":  dict(seed=7, base_hz=45,  dur=1.0, pinch=0.3, cheek=0.8, wobble=0.25, rel=0.9),
 "fart_question": dict(seed=8, base_hz=95,  dur=0.7, pitch_oct=-0.6, pitch_decay=0.7, wobble=0.15),
}
for name, kw in V.items():
    y = c.fart(**kw)
    y = c.toilet_bowl(y)
    # trim trailing silence
    a = np.abs(y); idx = np.where(a > 0.002 * a.max())[0]; y = y[: idx[-1] + int(0.05 * c.FS)]
    fade = min(len(y), int(0.03 * c.FS)); y[-fade:] *= np.linspace(1, 0, fade)
    y = y / (np.sqrt((y ** 2).mean()) * 4.5)   # loudness match
    y = np.tanh(y * 1.2) / np.tanh(1.2)          # soft limit, no harsh clipping
    y *= 0.95 / max(1.0, np.abs(y).max())
    wv.write(f"{out}/{name}.wav", c.FS, (y * 32767).astype(np.int16))
    print(name, f"{len(y)/c.FS:.2f}s")
