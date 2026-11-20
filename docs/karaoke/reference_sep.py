"""Reference MDX-Net (UVR_MDXNET_3_9662) separation. usage: sep.py model song_dir_or_file out_prefix seconds [dim_t ...]"""
import sys, glob, io, wave
import numpy as np, av, onnxruntime as ort

NFFT, HOP, DIMF, RATE = 6144, 1024, 2048, 44100
WIN = np.hanning(NFFT + 1)[:NFFT].astype(np.float32)  # periodic hann


def load(path, seconds):
    files = sorted(glob.glob(path + '/*.exo'), key=lambda f: int(f.split('.')[-4].split('\\')[-1].split('/')[-1]) if False else int(f.replace('\\', '/').split('/')[-1].split('.')[1]))
    data = b''.join(open(f, 'rb').read() for f in files)
    c = av.open(io.BytesIO(data))
    r = av.AudioResampler(format='fltp', layout='stereo', rate=RATE)
    out = []
    for fr in c.decode(audio=0):
        for g in r.resample(fr):
            out.append(g.to_ndarray())
    x = np.concatenate(out, axis=1)
    return x[:, :RATE * seconds]


def stft(x):  # x [2, n] -> [2, bins, frames] complex, centre padded (reflect) like torch
    p = np.pad(x, ((0, 0), (NFFT // 2, NFFT // 2)), mode='reflect')
    frames = 1 + (p.shape[1] - NFFT) // HOP
    idx = np.arange(NFFT)[None, :] + HOP * np.arange(frames)[:, None]
    return np.fft.rfft(p[:, idx] * WIN, axis=-1).transpose(0, 2, 1)


def istft(S, n):  # [2, bins, frames] -> [2, n]
    frames = S.shape[2]
    y = np.fft.irfft(S.transpose(0, 2, 1), n=NFFT, axis=-1) * WIN
    out = np.zeros((2, NFFT + HOP * (frames - 1)))
    norm = np.zeros(out.shape[1])
    for t in range(frames):
        out[:, t * HOP:t * HOP + NFFT] += y[:, t]
        norm[t * HOP:t * HOP + NFFT] += WIN ** 2
    out = out / np.maximum(norm, 1e-8)
    return out[:, NFFT // 2:NFFT // 2 + n]


def separate(sess, x, dim_t):
    chunk = HOP * (dim_t - 1)
    trim = NFFT // 2
    gen = chunk - 2 * trim
    n = x.shape[1]
    pad = gen - n % gen
    mix = np.concatenate([np.zeros((2, trim)), x, np.zeros((2, pad)), np.zeros((2, trim))], axis=1)
    out = []
    for i in range(0, n + pad, gen):
        seg = mix[:, i:i + chunk]
        S = stft(seg)[:, :DIMF]  # [2, 2048, dim_t]
        inp = np.stack([S[0].real, S[0].imag, S[1].real, S[1].imag])[None].astype(np.float32)
        o = sess.run(None, {'input': inp})[0][0]
        V = np.zeros((2, NFFT // 2 + 1, dim_t), complex)
        V[0, :DIMF] = o[0] + 1j * o[1]
        V[1, :DIMF] = o[2] + 1j * o[3]
        out.append(istft(V, chunk)[:, trim:chunk - trim])
    return np.concatenate(out, axis=1)[:, :n]


def save(path, x):
    pcm = (np.clip(x, -1, 1) * 32767).astype('<i2').T.tobytes()
    with wave.open(path, 'wb') as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(RATE); w.writeframes(pcm)


if __name__ == '__main__':
    model, song, prefix, secs = sys.argv[1:5]
    dims = [int(d) for d in sys.argv[5:]] or [256]
    o = ort.SessionOptions(); o.intra_op_num_threads = 4
    sess = ort.InferenceSession(model, o)
    x = load(song, int(secs))
    save(prefix + '_mix.wav', x)
    ref = None
    for d in dims:
        v = separate(sess, x, d)
        inst = x - v
        save('%s_inst_t%d.wav' % (prefix, d), inst)
        rms = lambda a: float(np.sqrt(np.mean(a ** 2)))
        msg = 'dim_t=%d mix=%.4f vocals=%.4f inst=%.4f' % (d, rms(x), rms(v), rms(inst))
        if ref is None:
            ref = v
        else:
            msg += ' diff_vs_first=%.3f' % (rms(v - ref) / rms(ref))
        print(msg, flush=True)
