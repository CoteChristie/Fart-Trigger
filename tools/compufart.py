# Python port of the "Terrance" physical model from CompuFart
# (c) 2023 Alex M. Fink, MIT License - https://github.com/alexmfink/compufart
import numpy as np
from numba import njit

FS = 44100

@njit(cache=True)
def terrance(freq, pressure, kc, xlo, xhi, fs):
    n = len(freq); out = np.zeros(n)
    pi = np.pi
    k = 105.0; kp = 50.0e8; QO = 3.0; QC = 0.5
    w = 0.007; l = 0.002; x0 = -0.001; y0 = -0.001; xJ = 0.0; yJ = 0.004
    yLo = -0.0004; yHi = 0.0002
    rho = 1.2; nu = 0.0000185; c = 340.0; SCup = 0.0025; ZC = rho * c / SCup
    xn = yn = xm1 = ym1 = xm2 = ym2 = 0.0
    p = 0.0; pLip = 0.0; Uac = 0.0; Uacm1 = 0.0; is_open = True
    T = 1.0 / fs
    for i in range(n):
        mass = 1.5 / (4.0 * pi * pi * (freq[i] / 16.0))
        rO = np.sqrt(mass * k) / QO; rC = np.sqrt(mass * k) / QC
        p0 = pressure[i]
        xm2 = xm1; ym2 = ym1; xm1 = xn; ym1 = yn
        r = rO if is_open else rC
        a = 2.0 * mass * fs * fs - r * fs - k
        xn = a * xm1; yn = a * ym1
        xn += 2.0 * w * (p0 - p) * (-ym1)
        yn += 2.0 * w * (p0 - p) * (xm1)
        b = -mass * fs * fs + r * fs
        xn += b * xm2; yn += b * ym2
        xn += k * x0; yn += k * y0
        xn += -2.0 * w * (p0 - p) * (-yJ)
        yn += -2.0 * w * (p0 - p) * (xJ)
        yn += 2.0 * w * l * pLip
        if is_open:
            xn += -0.5 * kc * (xm1 - x0) ** 3
            yn += -0.5 * kc * (ym1 - y0) ** 3
        if ym1 < yLo:
            d = ym1 - yLo
            yn += -0.5 * kp * d * d * d
        xn *= T * T / mass; yn *= T * T / mass
        if yn > yHi: yn = yHi
        if xn > xhi: xn = xhi
        elif xn < xlo: xn = xlo
        if yn > 0.0:
            is_open = True; SLip = 2.0 * w * yn
        else:
            is_open = False; SLip = 0.0
        ULip = w * ((xn - xJ) * (yn - ym1) * fs - (yn - yJ) * (xn - xm1) * fs)
        if is_open:
            Uacm1 = Uac
            s2 = SLip * SLip; s3 = s2 * SLip; sc = SLip * SCup
            if SLip == 0.0 or s2 == 0.0 or s3 == 0.0 or sc == 0.0:
                Uac = 0.0; pLip = 0.0
            else:
                aa = 0.5 * rho / s2 - rho * (1.0 / sc - 1.0 / (SCup * SCup))
                bb = rho * l * fs / SLip + 12.0 * nu * w * w * l / s3
                cc = p - p0 - rho * l * Uacm1 * fs / SLip
                rad = bb * bb - 4.0 * aa * cc
                rt = 0.0 if rad < 0.0 else np.sqrt(rad)
                Uac = (-bb + rt) / (2.0 * aa)
                pLip = p0 - 0.5 * rho * Uac * Uac / s2 - rho * l * (Uac - Uacm1) * fs / SLip - 12.0 * nu * w * w * l * Uac / s3
        else:
            Uac = 0.0; pLip = 0.0
        total = ULip + Uac
        p = ZC * total
        out[i] = total
    return out

def adsr(n, a, d, s, hold, rel, fs=FS):
    t = np.arange(n) / fs
    env = np.zeros(n)
    on_end = a + d + hold
    env = np.where(t < a, t / max(a, 1e-6), env)
    m = (t >= a) & (t < a + d); env[m] = 1 - (1 - s) * (t[m] - a) / max(d, 1e-6)
    m = (t >= a + d) & (t < on_end); env[m] = s
    m = t >= on_end; env[m] = s * np.maximum(0, 1 - (t[m] - on_end) / max(rel, 1e-6))
    return env

def fart(seed, base_hz, dur, pinch=0.5, cheek=0.5, strain=0.7, intensity=0.5,
         pitch_oct=2.0, pitch_decay=0.3, rel=0.85, sputter=0, wobble=0.0, fs=FS):
    rng = np.random.default_rng(seed)
    n = int(fs * (dur + rel + 0.2))
    hold = dur
    penv = adsr(n, 0.005, 0.02, 0.8, hold, rel, fs)
    # sputter: brief pressure dips that make it stutter
    for _ in range(sputter):
        c = rng.uniform(0.1, 0.9) * (hold + rel * 0.5) * fs
        wdt = rng.uniform(0.015, 0.05) * fs
        penv *= 1 - 0.85 * np.exp(-((np.arange(n) - c) / wdt) ** 2)
    # pressure -> physical units (TerranceInputInterface)
    a = np.clip(1 - ((intensity * 10000 + 100) * 2 * np.pi / fs), 0, 0.999)
    noise = np.empty(n); prev = 0.0
    bip = rng.uniform(-1, 1, n)
    for i in range(n):
        r = 2 * (1 - a) * bip[i] + a * prev; prev = r; noise[i] = r
    mag = min(0.35 * strain, 0.35)
    pres = np.where(penv < 0.05, penv / 0.05 * 4800,
                    ((penv - 0.05) / 0.95 * 3200 + 4800) * 0.95 * (1 + mag * noise))
    # pitch envelope (octaves) + slow random wobble
    pit = pitch_oct * adsr(n, 0.005, pitch_decay, 0.0, hold, 0.25, fs)
    if wobble:
        w = np.cumsum(rng.standard_normal(n)) ; w -= np.linspace(w[0], w[-1], n)
        w /= (np.abs(w).max() + 1e-9); pit += wobble * w
    freq = base_hz * 2 ** pit
    kc = ((pinch * 0.75 + 0.25) ** 2) * 4.2e8
    xhi = 0.0012 * cheek ** 2 + 0.0015
    xlo = -0.0006 * (1 - cheek) ** 2
    flow = terrance(freq, pres, kc, xlo, xhi, float(fs))
    # output interface: gain + DC block
    x = 2500 * flow; y = np.zeros(n); lo = li = 0.0
    for i in range(n):
        lo = 0.99 * lo + 0.995 * x[i] - 0.995 * li; li = x[i]; y[i] = lo
    return y

def toilet_bowl(y, fs=FS, mix=0.18):
    # tiny porcelain-room reverb: a few short feedback combs
    out = y.copy()
    for ms, g in [(4.3, 0.55), (6.1, 0.5), (8.9, 0.45), (13.7, 0.4)]:
        d = int(fs * ms / 1000); buf = np.zeros(len(y))
        for i in range(len(y)):
            buf[i] = y[i] + (g * buf[i - d] if i >= d else 0)
        out += mix * buf / 4
    return out
