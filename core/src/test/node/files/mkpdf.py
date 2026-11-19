"""시험용 2쪽짜리 작은 PDF(sample.pdf) 만들기: python mkpdf.py <출력>"""
import sys

objs = []
pages = []
font = 3
objs.append(b"<< /Type /Catalog /Pages 2 0 R >>")
objs.append(None)  # pages, 나중에
objs.append(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
for i, (txt, color) in enumerate([("Jam shared PDF - page 1", "0.18 0.9 0.65"), ("Page 2 - hello phone", "1 0.42 0.42")]):
    content = ("%s rg 40 600 520 160 re f 0 0 0 rg BT /F1 36 Tf 60 500 Td (%s) Tj ET" % (color, txt)).encode()
    objs.append(b"<< /Length %d >>\nstream\n" % len(content) + content + b"\nendstream")
    cnum = len(objs)
    objs.append(b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents %d 0 R >>" % cnum)
    pages.append(len(objs))
objs[1] = ("<< /Type /Pages /Kids [%s] /Count %d >>" % (" ".join("%d 0 R" % p for p in pages), len(pages))).encode()

out = b"%PDF-1.4\n"
offs = []
for i, o in enumerate(objs):
    offs.append(len(out))
    out += b"%d 0 obj\n" % (i + 1) + o + b"\nendobj\n"
x = len(out)
out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objs) + 1)
for o in offs:
    out += b"%010d 00000 n \n" % o
out += b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objs) + 1, x)
open(sys.argv[1], "wb").write(out)
print(len(out))
