#!/usr/bin/env python3
"""Render a Blockbench .bbmodel item model (with embedded texture) to a 16x16 icon.

Usage:
    python3 tools/render_bbmodel_icon.py path/to/Model.bbmodel out_icon.png [--preview out_preview.png] [--size 256]

The icon is a software render of the model using its own texture and the
author's GUI display transform — no generated art. Preview shows the same
render at higher resolution for QA.
"""
import argparse
import base64
import json
import math
import sys

from PIL import Image, ImageDraw


def load_model(path):
    """Load a Blockbench .bbmodel or a Java/OptiFine item model .json.

    Returns (normalized_model, texture_image): face UVs are in texture pixels,
    element rotation is a bbmodel-style [x, y, z] Euler around 'origin'.
    """
    import io
    import os
    with open(path, encoding='utf-8') as f:
        m = json.load(f)

    if 'texture_size' in m and isinstance(m.get('textures'), dict):
        # ---- Java/OptiFine item model
        tex_ref = m['textures'].get('0') or next(iter(m['textures'].values()))
        tex_ref = tex_ref.lstrip('./')
        tex_path = os.path.join(os.path.dirname(path), tex_ref + '.png')
        texture = Image.open(tex_path).convert('RGBA')
        tw, th = texture.size
        if th > tw and th % tw == 0:  # animated: stacked square frames
            texture = texture.crop((0, 0, tw, tw))
        sx, sy = m.get('texture_size', [16, 16])[0] / 16.0, m.get('texture_size', [16, 16])[1] / 16.0
        elements = []
        for el in m['elements']:
            rot = el.get('rotation') or {}
            angle = rot.get('angle', 0) or 0
            axis = rot.get('axis', 'y')
            euler = [angle if axis == 'x' else 0,
                     angle if axis == 'y' else 0,
                     angle if axis == 'z' else 0]
            faces = {}
            for key, f in (el.get('faces') or {}).items():
                if not f.get('uv'):
                    continue
                u1, v1, u2, v2 = f['uv']
                faces[key] = {'uv': [u1 * sx, v1 * sy, u2 * sx, v2 * sy], 'texture': 0}
            elements.append({'from': el['from'], 'to': el['to'],
                             'rotation': euler,
                             'origin': rot.get('origin') or [8, 8, 8],
                             'faces': faces})
        return {'elements': elements, 'display': m.get('display') or {}}, texture

    # ---- Blockbench .bbmodel
    tex = m['textures'][0]
    src = tex.get('source') or tex.get('data') or ''
    if src.startswith('data:'):
        raw = base64.b64decode(src.split(',', 1)[1])
    else:
        with open(src, 'rb') as f:
            raw = f.read()
    texture = Image.open(io.BytesIO(raw)).convert('RGBA')
    return m, texture


def rot_xyz(v, rx, ry, rz):
    x, y, z = v
    if rx:
        a = math.radians(rx)
        y, z = y * math.cos(a) - z * math.sin(a), y * math.sin(a) + z * math.cos(a)
    if ry:
        a = math.radians(ry)
        x, z = x * math.cos(a) + z * math.sin(a), -x * math.sin(a) + z * math.cos(a)
    if rz:
        a = math.radians(rz)
        x, y = x * math.cos(a) - y * math.sin(a), x * math.sin(a) + y * math.cos(a)
    return (x, y, z)


def cube_faces(el):
    x0, y0, z0 = el['from']
    x1, y1, z1 = el['to']
    x0, x1 = min(x0, x1), max(x0, x1)
    y0, y1 = min(y0, y1), max(y0, y1)
    z0, z1 = min(z0, z1), max(z0, z1)
    # UL, LL, LR, UR as seen from outside the face
    return {
        'north': [(x1, y1, z0), (x1, y0, z0), (x0, y0, z0), (x0, y1, z0)],
        'south': [(x0, y1, z1), (x0, y0, z1), (x1, y0, z1), (x1, y1, z1)],
        'east':  [(x1, y1, z1), (x1, y0, z1), (x1, y0, z0), (x1, y1, z0)],
        'west':  [(x0, y1, z0), (x0, y0, z0), (x0, y0, z1), (x0, y1, z1)],
        'up':    [(x0, y1, z0), (x0, y1, z1), (x1, y1, z1), (x1, y1, z0)],
        'down':  [(x0, y0, z1), (x0, y0, z0), (x1, y0, z0), (x1, y0, z1)],
    }


SHADE = {'up': 1.0, 'down': 0.55, 'north': 0.8, 'south': 0.8, 'east': 0.62, 'west': 0.62}


def rot_seq(v, rot, order='xyz'):
    """Apply Euler rotations in the given order ('xyz' = X first; 'zyx' = Z first)."""
    out = v
    for ax in order:
        out = rot_xyz(out,
                      rot[0] if ax == 'x' else 0,
                      rot[1] if ax == 'y' else 0,
                      rot[2] if ax == 'z' else 0)
    return out


def build_faces(m, rot_order='xyz'):
    faces = []
    for el in m['elements']:
        rot = el.get('rotation') or [0, 0, 0]
        org = el.get('origin') or [8, 8, 8]
        for key, corners in cube_faces(el).items():
            spec = (el.get('faces') or {}).get(key)
            if not spec or spec.get('texture') is None or spec.get('texture') == '#missing':
                continue
            out = []
            for c in corners:
                v = (c[0] - org[0], c[1] - org[1], c[2] - org[2])
                v = rot_seq(v, rot, rot_order)
                out.append((v[0] + org[0], v[1] + org[1], v[2] + org[2]))
            faces.append({'key': key, 'corners': out, 'uv': spec['uv']})
    return faces


def render(m, texture, size=256, rot_order='xyz'):
    faces = build_faces(m, rot_order)
    disp = (m.get('display') or {}).get('gui') or {}
    rot = disp.get('rotation') or [30, 225, 0]
    tr = disp.get('translation') or [0, 0, 0]
    sc = disp.get('scale') or [1, 1, 1]

    def gui(v):
        v = ((v[0] - 8) * sc[0], (v[1] - 8) * sc[1], (v[2] - 8) * sc[2])
        v = rot_seq(v, rot, rot_order)
        return (v[0] + tr[0], v[1] + tr[1], v[2] + tr[2])

    for f in faces:
        f['proj'] = [(gui(c)[0], -gui(c)[1], gui(c)[2]) for c in f['corners']]

    xs = [p[0] for f in faces for p in f['proj']]
    ys = [p[1] for f in faces for p in f['proj']]
    pad = 0.03
    w, h = max(xs) - min(xs), max(ys) - min(ys)
    zoom = (size * (1 - 2 * pad)) / max(w, h, 1e-6)
    ox = (size - w * zoom) / 2 - min(xs) * zoom
    oy = (size - h * zoom) / 2 - min(ys) * zoom

    canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    tw, th = texture.size

    def solve_affine(o_pts, i_pts):
        import numpy as np
        M = np.array([[o_pts[0][0], o_pts[0][1], 1],
                      [o_pts[1][0], o_pts[1][1], 1],
                      [o_pts[2][0], o_pts[2][1], 1]], dtype=float)
        I = np.array(i_pts, dtype=float)
        co = np.linalg.solve(M, I)  # 3x2
        return (co[0, 0], co[1, 0], co[2, 0], co[0, 1], co[1, 1], co[2, 1])

    faces.sort(key=lambda f: sum(p[2] for p in f['proj']) / 4)  # far first
    for f in faces:
        pts = [(p[0] * zoom + ox, p[1] * zoom + oy) for p in f['proj']]
        area = sum((pts[i][0] - pts[(i + 1) % 4][0]) * (pts[i][1] + pts[(i + 1) % 4][1])
                   for i in range(4)) / 2
        if abs(area) < 1e-6:
            continue
        u1, v1, u2, v2 = f['uv']
        U1, V1, U2, V2 = min(u1, u2), min(v1, v2), max(u1, u2), max(v1, v2)
        # uv values are texture pixels directly (resolution == texture size here)
        U1p, V1p, U2p, V2p = int(U1), int(V1), max(int(U2), int(U1) + 1), max(int(V2), int(V1) + 1)
        try:
            coeffs = solve_affine(
                [pts[0], pts[1], pts[3]],
                [(U1p, V1p), (U1p, V2p), (U2p, V1p)],
            )
        except Exception:
            continue
        warped = texture.transform((size, size), Image.AFFINE, coeffs,
                                   resample=Image.BILINEAR)
        mask = Image.new('L', (size, size), 0)
        ImageDraw.Draw(mask).polygon([tuple(p) for p in pts], fill=255)
        shade = SHADE.get(f['key'], 0.8)
        if shade < 1.0:
            black = Image.new('RGBA', (size, size), (0, 0, 0, 255))
            warped = Image.composite(warped, black,
                                     Image.new('L', (size, size), int(255 * shade)))
        canvas = Image.alpha_composite(canvas, Image.composite(
            warped, Image.new('RGBA', (size, size), (0, 0, 0, 0)), mask))
    return canvas


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('bbmodel')
    ap.add_argument('out')
    ap.add_argument('--preview', default=None)
    ap.add_argument('--size', type=int, default=256)
    ap.add_argument('--rot-order', default=None,
                    help="Euler order for display rotation: 'xyz' (Blockbench) or 'zyx' (Java/JOML). "
                         "Auto-detected from file type.")
    args = ap.parse_args()

    m, texture = load_model(args.bbmodel)
    rot_order = args.rot_order or ('zyx' if args.bbmodel.endswith('.json') else 'xyz')
    big = render(m, texture, args.size, rot_order)
    if args.preview:
        big.save(args.preview)
    from PIL import ImageFilter
    icon = big.resize((16, 16), Image.BOX)
    px = icon.load()
    for yy in range(16):
        for xx in range(16):
            r, g, b, a = px[xx, yy]
            px[xx, yy] = (r, g, b, 255 if a > 16 else 0)
    r, g, b, a = icon.split()
    a = a.filter(ImageFilter.MaxFilter(5)).filter(ImageFilter.MinFilter(5))
    icon = Image.merge('RGBA', (r, g, b, a))
    icon.save(args.out)
    print(f'OK: {args.out} (16x16)' + (f', preview {args.preview}' if args.preview else ''))


if __name__ == '__main__':
    main()
