"""
合成训练数据生成器：棋子交叉点小图（48x48）。
类别：0=空点，1..7=帅/士/象/马/车/炮/兵（字形组，颜色由磁盘色另判）。

用法:
    python gen_data.py [--per-class 12000] [--out dataset]
"""
import argparse
import io
import json
import os
import random
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from fonts import ensure_fonts  # noqa: E402

SIZE = 96   # 先画大图，再缩到 48
OUT = 48

CLASSES = ["empty", "king", "advisor", "elephant", "horse", "rook", "cannon", "pawn"]

# 每类的字形（红黑通用，颜色另绘）
GLYPHS = {
    "king": ["帅", "帥", "将", "將"],
    "advisor": ["仕", "士"],
    "elephant": ["相", "象"],
    "horse": ["马", "馬"],
    "rook": ["车", "車"],
    "cannon": ["炮", "砲"],
    "pawn": ["兵", "卒"],
}

RED_INK = [(180, 30, 30), (200, 45, 40), (160, 25, 25), (220, 60, 50)]
BLACK_INK = [(20, 20, 20), (45, 40, 35), (60, 50, 45)]
WOOD_DISK = [(247, 231, 195), (235, 202, 150), (240, 220, 170), (222, 190, 130)]
DARK_DISK = [(184, 134, 11), (140, 95, 35), (170, 120, 50)]
BOARD_BG = [(232, 200, 143), (224, 188, 128), (245, 222, 173), (210, 170, 110)]
LINE = (90, 58, 30)


def rand_disk_style(img, draw, glyph, ink):
    """在 img(96x96) 上画一枚棋子，返回 None"""
    style = random.random()
    if style < 0.55:
        # 经典木色圆盘 + 双环
        bg = random.choice(WOOD_DISK)
        cx = cy = SIZE // 2
        r = random.randint(38, 44)
        draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=bg,
                     outline=random.choice([ink, LINE]), width=2)
        r2 = r - random.randint(5, 8)
        draw.ellipse([cx - r2, cy - r2, cx + r2, cy + r2], outline=ink, width=2)
        fs = int(r2 * random.uniform(1.05, 1.3))
    elif style < 0.8:
        # 深色圆盘（某些 App 风格），字用浅色保证对比度
        bg = random.choice(DARK_DISK)
        cx = cy = SIZE // 2
        r = random.randint(38, 44)
        ring = (240, 210, 140)
        draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=bg, outline=ring, width=3)
        fs = int(r * random.uniform(1.0, 1.2))
        ink = (245, 235, 200)
    elif style < 0.92:
        # 书本图谱：纸上墨字，无盘
        fs = random.randint(52, 68)
    else:
        # 白盘黑字 / 黑盘白字（部分 App）
        dark = random.random() < 0.5
        cx = cy = SIZE // 2
        r = random.randint(38, 44)
        draw.ellipse([cx - r, cy - r, cx + r, cy + r],
                     fill=(30, 30, 30) if dark else (250, 250, 245),
                     outline=(200, 170, 90), width=2)
        return fs_override(int(r * 1.1), (245, 245, 240) if dark else ink)
    return fs_override(fs, ink)


def fs_override(fs, ink):
    return fs, ink


def render_empty():
    """空点：棋盘底 + 过线（楚河区域则无线）"""
    bg = random.choice(BOARD_BG)
    img = Image.new("RGB", (SIZE, SIZE), bg)
    d = ImageDraw.Draw(img)
    # 轻微木纹噪声
    for _ in range(random.randint(0, 6)):
        x0 = random.randint(0, SIZE)
        y0 = random.randint(0, SIZE)
        x1 = x0 + random.randint(-30, 30)
        y1 = y0 + random.randint(-4, 4)
        c = tuple(max(0, min(255, v + random.randint(-14, 14))) for v in bg)
        d.line([x0, y0, x1, y1], fill=c, width=1)
    if random.random() < 0.75:
        w = random.choice([2, 3, 4])
        off = random.randint(-3, 3)
        d.line([0, SIZE // 2 + off, SIZE, SIZE // 2 + off], fill=LINE, width=w)
        off2 = random.randint(-3, 3)
        d.line([SIZE // 2 + off2, 0, SIZE // 2 + off2, SIZE], fill=LINE, width=w)
    return img


def render_piece(glyph, fonts):
    """棋子：底色（棋盘）+ 圆盘 + 字"""
    img = render_empty()
    d = ImageDraw.Draw(img)
    # 盘外漏出棋盘：随机不完全遮盖
    ink = random.choice(RED_INK) if random.random() < 0.5 else random.choice(BLACK_INK)
    fs, ink2 = rand_disk_style(img, d, glyph, ink)
    font = ImageFont.truetype(random.choice(fonts), fs)
    # 文字颜色（深盘白字的情况已在 style 内处理为 ink2）
    d2 = ImageDraw.Draw(img)
    bbox = d2.textbbox((0, 0), glyph, font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    cx = SIZE / 2 + random.randint(-2, 2)
    cy = SIZE / 2 + random.randint(-2, 2)
    d2.text((cx - tw / 2 - bbox[0], cy - th / 2 - bbox[1]), glyph, font=font, fill=ink2)
    return img


def augment(img):
    # 旋转
    ang = random.uniform(-12, 12)
    img = img.rotate(ang, resample=Image.BILINEAR, expand=False,
                     fillcolor=img.getpixel((0, 0)))
    # 缩放
    s = random.uniform(0.78, 1.15)
    ns = int(SIZE * s)
    img = img.resize((ns, ns), Image.BILINEAR)
    # 居中裁回 SIZE
    x0 = (ns - SIZE) // 2
    y0 = (ns - SIZE) // 2
    img = img.crop((x0, y0, x0 + SIZE, y0 + SIZE))
    # 亮度对比
    img = ImageEnhance.Brightness(img).enhance(random.uniform(0.75, 1.25))
    img = ImageEnhance.Contrast(img).enhance(random.uniform(0.75, 1.3))
    if random.random() < 0.5:
        img = img.filter(ImageFilter.GaussianBlur(random.uniform(0.2, 1.2)))
    # JPEG 压缩伪影
    if random.random() < 0.7:
        q = random.randint(55, 95)
        buf = io.BytesIO()
        img.save(buf, "JPEG", quality=q)
        buf.seek(0)
        img = Image.open(buf).convert("RGB")
    return img.resize((OUT, OUT), Image.BILINEAR)


def gen_class(ci, cls, n, fonts):
    rng = random.Random(1000 + ci)
    out = np.zeros((n, OUT, OUT, 3), dtype=np.uint8)
    for k in range(n):
        if cls == "empty":
            img = render_empty()
        else:
            g = rng.choice(GLYPHS[cls])
            img = render_piece(g, fonts)
        out[k] = np.asarray(augment(img), dtype=np.uint8)
    return ci, out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--per-class", type=int, default=12000)
    ap.add_argument("--out", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "dataset"))
    ap.add_argument("--jobs", type=int, default=4)
    args = ap.parse_args()

    fonts = ensure_fonts()
    print("可用字体:", fonts)

    from multiprocessing import Pool
    jobs = [(ci, cls, args.per_class, fonts) for ci, cls in enumerate(CLASSES)]
    os.makedirs(args.out, exist_ok=True)

    xs, ys = [], []
    with Pool(args.jobs) as pool:
        for ci, arr in pool.imap_unordered(_worker, jobs):
            print(f"  {CLASSES[ci]}: {arr.shape[0]} 张")
            xs.append(arr)
            ys.append(np.full(arr.shape[0], ci, dtype=np.uint8))
    x = np.concatenate(xs)
    y = np.concatenate(ys)
    idx = np.random.RandomState(42).permutation(len(x))
    x, y = x[idx], y[idx]
    np.save(os.path.join(args.out, "x.npy"), x)
    np.save(os.path.join(args.out, "y.npy"), y)
    with open(os.path.join(args.out, "labels.json"), "w", encoding="utf-8") as f:
        json.dump({"classes": CLASSES, "size": OUT}, f, ensure_ascii=False, indent=1)
    print("完成:", x.shape, "->", args.out)


def _worker(t):
    ci, cls, n, fonts = t
    return gen_class(ci, cls, n, fonts)


if __name__ == "__main__":
    main()
