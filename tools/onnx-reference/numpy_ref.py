"""
Эталон для интерпретатора ONNX мода: исполняет граф Basic Pitch средствами numpy
(np.pad, sliding_window_view + einsum для свёрток) и сравнивает с выходами Java.

  NBMUSIC_DUMP=/tmp/dump ./gradlew test --tests '*BasicPitchTest*'
  python3 tools/onnx-reference/numpy_ref.py src/main/resources/nbmusic/basic-pitch/nmp.onnx /tmp/dump
"""
import os, sys
import numpy as np
from numpy.lib.stride_tricks import sliding_window_view
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import onnxdump as od


def to_np(t):
    dims = tuple(t['dims'])
    if t['type'] == 1:
        a = np.frombuffer(t['raw'], dtype='<f4') if t['raw'] else np.array(t['float'], dtype=np.float32)
    elif t['type'] in (6, 7):
        a = (np.frombuffer(t['raw'], dtype='<i8' if t['type'] == 7 else '<i4').astype(np.int64)
             if t['raw'] else np.array(t['int64'] or t['int32'], dtype=np.int64))
    elif t['type'] == 9:
        a = np.frombuffer(t['raw'], dtype=np.uint8).astype(bool)
    else:
        raise ValueError(t['type'])
    return a.reshape(dims)


def attrs(n):
    return {a['name']: a.get('i', a.get('f', a.get('s', a['ints'] or a['floats']))) for a in n['attrs']}


def conv(x, w, b, at):
    sh, sw = at.get('strides', [1, 1]); pads = at.get('pads', [0, 0, 0, 0])
    assert at.get('group', 1) == 1 and at.get('dilations', [1, 1]) == [1, 1]
    xp = np.pad(x, ((0, 0), (0, 0), (pads[0], pads[2]), (pads[1], pads[3])))
    win = sliding_window_view(xp, (w.shape[2], w.shape[3]), axis=(2, 3))[:, :, ::sh, ::sw]
    y = np.einsum('ncyxij,ocij->noyx', win.astype(np.float64), w.astype(np.float64))
    if b is not None:
        y += b.reshape(1, -1, 1, 1)
    return y.astype(np.float32)


def run(g, feed):
    v = {t['name']: to_np(t) for t in g['init']}
    v.update(feed)
    for n in g['nodes']:
        i = [v[name] if name else None for name in n['in']]
        a = attrs(n); op = n['op']
        if op == 'Reshape':
            r = i[0].reshape([int(s) if s != 0 else i[0].shape[k] for k, s in enumerate(i[1])])
        elif op == 'Unsqueeze':
            r = i[0]; rank = r.ndim + len(i[1])
            for ax in sorted(int(x) % rank for x in i[1]):
                r = np.expand_dims(r, ax)
        elif op == 'Transpose': r = np.transpose(i[0], a.get('perm'))
        elif op == 'Concat': r = np.concatenate(i, axis=a['axis'])
        elif op == 'Slice':
            x = i[0]; axes = i[3] if len(i) > 3 and i[3] is not None else range(len(i[1]))
            steps = i[4] if len(i) > 4 and i[4] is not None else [1] * len(i[1])
            sl = [slice(None)] * x.ndim
            for s, e, ax, st in zip(i[1], i[2], axes, steps):
                sl[int(ax)] = slice(int(max(min(s, 2 ** 62), -2 ** 62)), int(max(min(e, 2 ** 62), -2 ** 62)), int(st))
            r = x[tuple(sl)]
        elif op == 'Pad':
            p = [int(x) for x in i[1]]; nd = i[0].ndim
            pw = [(p[k], p[k + nd]) for k in range(nd)]
            cv = float(i[2]) if len(i) > 2 and i[2] is not None else 0.0
            r = np.pad(i[0], pw, mode='reflect') if a.get('mode', 'constant') == 'reflect' else np.pad(i[0], pw, constant_values=cv)
        elif op == 'Conv': r = conv(i[0], i[1], i[2] if len(i) > 2 else None, a)
        elif op == 'Neg': r = -i[0]
        elif op == 'Relu': r = np.maximum(i[0], 0)
        elif op == 'Sigmoid': r = (1 / (1 + np.exp(-i[0].astype(np.float64)))).astype(np.float32)
        elif op == 'Sqrt': r = np.sqrt(i[0])
        elif op == 'Log': r = np.log(i[0])
        elif op == 'Add': r = i[0] + i[1]
        elif op == 'Sub': r = i[0] - i[1]
        elif op == 'Mul': r = i[0] * i[1]
        elif op == 'Div': r = i[0] / i[1]
        elif op == 'Equal': r = np.equal(i[0], i[1])
        elif op == 'Where': r = np.where(i[0], i[1], i[2])
        elif op == 'Cast': r = i[0].astype({1: np.float32, 6: np.int32, 7: np.int64, 9: bool}[a['to']])
        elif op == 'Shape': r = np.array(i[0].shape, dtype=np.int64)
        elif op == 'ReduceSum':
            axes = tuple(int(x) for x in i[1]) if len(i) > 1 and i[1] is not None and len(i[1]) else None
            r = i[0] if axes is None and a.get('noop_with_empty_axes', 0) else np.sum(i[0], axis=axes, keepdims=bool(a.get('keepdims', 1)))
        elif op in ('ReduceMax', 'ReduceMin'):
            r = (np.max if op == 'ReduceMax' else np.min)(i[0], axis=tuple(a['axes']), keepdims=bool(a.get('keepdims', 1)))
        else:
            raise NotImplementedError(op)
        v[n['out'][0]] = np.asarray(r)
    return v


if __name__ == '__main__':
    g = od.load(sys.argv[1]); dump = sys.argv[2]
    x = np.fromfile(f'{dump}/input.f32', dtype='<f4').reshape(1, -1, 1)
    v = run(g, {g['inputs'][0][0]: x})
    worst = 0
    for name, _ in g['outputs']:
        ref = v[name].astype(np.float32).reshape(-1)
        java = np.fromfile(f"{dump}/{name.replace(':', '_')}.f32", dtype='<f4')
        d = np.abs(ref - java); worst = max(worst, d.max())
        print(f'{name}: max diff={d.max():.2e}, mean diff={d.mean():.2e}, >0.5: ref {int((ref > 0.5).sum())} java {int((java > 0.5).sum())}')
    sys.exit(0 if worst < 1e-3 else 1)
