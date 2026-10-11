"""Минимальный разбор ONNX (protobuf) без зависимостей: узлы, атрибуты, веса."""
import struct, sys, collections


def varint(b, i):
    r = s = 0
    while True:
        x = b[i]; i += 1
        r |= (x & 0x7F) << s; s += 7
        if x < 0x80:
            return r, i


def fields(b):
    i = 0; out = []
    while i < len(b):
        key, i = varint(b, i); f, t = key >> 3, key & 7
        if t == 0: v, i = varint(b, i)
        elif t == 1: v = b[i:i + 8]; i += 8
        elif t == 2:
            n, i = varint(b, i); v = b[i:i + n]; i += n
        elif t == 5: v = b[i:i + 4]; i += 4
        else: raise ValueError(t)
        out.append((f, t, v))
    return out


def packed_varints(v):
    i = 0; r = []
    while i < len(v):
        x, i = varint(v, i); r.append(x if x < 1 << 63 else x - (1 << 64))
    return r


def tensor(b):
    d = {'dims': [], 'type': 0, 'name': '', 'raw': b'', 'float': [], 'int64': [], 'int32': []}
    for f, t, v in fields(b):
        if f == 1: d['dims'] += packed_varints(v) if t == 2 else [v]
        elif f == 2: d['type'] = v
        elif f == 8: d['name'] = v.decode()
        elif f == 9: d['raw'] = v
        elif f == 4: d['float'] += list(struct.unpack(f'<{len(v) // 4}f', v)) if t == 2 else [struct.unpack('<f', v)[0]]
        elif f == 7: d['int64'] += packed_varints(v) if t == 2 else [v]
        elif f == 5: d['int32'] += packed_varints(v) if t == 2 else [v]
    return d


def attr(b):
    a = {'name': '', 'ints': [], 'floats': []}
    for f, t, v in fields(b):
        if f == 1: a['name'] = v.decode()
        elif f == 2: a['f'] = struct.unpack('<f', v)[0]
        elif f == 3: a['i'] = v if v < 1 << 63 else v - (1 << 64)
        elif f == 4: a['s'] = v.decode(errors='replace')
        elif f == 5: a['t'] = tensor(v)
        elif f == 7: a['floats'] += list(struct.unpack(f'<{len(v) // 4}f', v)) if t == 2 else [struct.unpack('<f', v)[0]]
        elif f == 8: a['ints'] += packed_varints(v) if t == 2 else [v]
    return a


def node(b):
    n = {'in': [], 'out': [], 'name': '', 'op': '', 'attrs': []}
    for f, t, v in fields(b):
        if f == 1: n['in'].append(v.decode())
        elif f == 2: n['out'].append(v.decode())
        elif f == 3: n['name'] = v.decode()
        elif f == 4: n['op'] = v.decode()
        elif f == 5: n['attrs'].append(attr(v))
    return n


def valueinfo(b):
    name = ''
    for f, t, v in fields(b):
        if f == 1: name = v.decode()
    return name, None


def load(path):
    model = fields(open(path, 'rb').read())
    graph = next(v for f, t, v in model if f == 7)
    g = {'nodes': [], 'init': [], 'inputs': [], 'outputs': []}
    for f, t, v in fields(graph):
        if f == 1: g['nodes'].append(node(v))
        elif f == 5: g['init'].append(tensor(v))
        elif f == 11: g['inputs'].append(valueinfo(v))
        elif f == 12: g['outputs'].append(valueinfo(v))
    return g


if __name__ == '__main__':
    g = load(sys.argv[1])
    print('inputs', g['inputs']); print('outputs', g['outputs'])
    print('nodes', len(g['nodes']), 'initializers', len(g['init']))
    print(collections.Counter(n['op'] for n in g['nodes']))
