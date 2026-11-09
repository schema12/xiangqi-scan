"""字体下载与加载：开源中文字体 + 本地系统字体回退。"""
import os
import urllib.request

FONT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fonts")

# 开源中文字体（Google Fonts 仓库原始文件）
URLS = [
    # 霞鹜文楷（楷体风，最像棋子字）
    ("LXGWWenKai-Regular.ttf",
     "https://raw.githubusercontent.com/google/fonts/main/ofl/lxgwwenkai/LXGWWenKai-Regular.ttf"),
    # 站酷小薇（印刷宋/楷风）
    ("ZCOOLXiaoWei-Regular.ttf",
     "https://raw.githubusercontent.com/google/fonts/main/ofl/zcoolxiaowei/ZCOOLXiaoWei-Regular.ttf"),
    # 马善政毛笔楷书
    ("MaShanZheng-Regular.ttf",
     "https://raw.githubusercontent.com/google/fonts/main/ofl/mashanzheng/MaShanZheng-Regular.ttf"),
    # 龙藏体（手写风）
    ("LongCang-Regular.ttf",
     "https://raw.githubusercontent.com/google/fonts/main/ofl/longcang/LongCang-Regular.ttf"),
    # Noto Serif SC（宋体，可变字体）
    ("NotoSerifSC.ttf",
     "https://raw.githubusercontent.com/google/fonts/main/ofl/notoserifsc/NotoSerifSC%5Bwght%5D.ttf"),
    # Noto Sans SC（黑体，可变字体）
    ("NotoSansSC.ttf",
     "https://raw.githubusercontent.com/google/fonts/main/ofl/notosanssc/NotoSansSC%5Bwght%5D.ttf"),
]

# Windows 本地字体回退（存在即用）
LOCAL_FALLBACKS = [
    r"C:\Windows\Fonts\simkai.ttf",   # 楷体
    r"C:\Windows\Fonts\STKAITI.TTF",
    r"C:\Windows\Fonts\simhei.ttf",   # 黑体
    r"C:\Windows\Fonts\msyh.ttc",     # 微软雅黑
    r"C:\Windows\Fonts\simsun.ttc",   # 宋体
]


def ensure_fonts():
    """本地系统中文字体优先；没有（云端 Linux）才下载开源字体。
    设 GEN_FONTS_FORCE=1 强制全部下载。"""
    got = []
    for p in LOCAL_FALLBACKS:
        if os.path.exists(p):
            got.append(p)
    if got and os.environ.get("GEN_FONTS_FORCE") != "1":
        print("使用本地字体:", got)
        return got
    os.makedirs(FONT_DIR, exist_ok=True)
    for name, url in URLS:
        path = os.path.join(FONT_DIR, name)
        if os.path.exists(path) and os.path.getsize(path) > 100000:
            got.append(path)
            continue
        try:
            print("下载字体:", name)
            urllib.request.urlretrieve(url, path)
            got.append(path)
        except Exception as e:
            print("  下载失败:", e)
    if not got:
        raise RuntimeError("没有任何可用中文字体")
    return got


if __name__ == "__main__":
    print(ensure_fonts())
