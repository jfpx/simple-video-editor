"""Independent PNG fixture/math and frozen-pack host tests; no Android or installed image package."""
import struct
import subprocess
import unittest
import zlib

import build_watermark_oracle as watermark


class WatermarkFixtureTests(unittest.TestCase):
    def test_windows_checkout_preserves_manifest_pin(self):
        path = "app/src/main/assets/watermark-oracle/manifest.json"
        checkout = subprocess.check_output(
            ["git", "-c", "core.autocrlf=true", "cat-file", "--filters",
             "--path=" + path, "HEAD:" + path],
            cwd=watermark.ASSETS.parents[4], timeout=30)
        self.assertNotIn(b"\r\n", checkout)
        self.assertEqual(checkout, (watermark.ASSETS / "manifest.json").read_bytes())

    def test_original_rgba_png_crc_dimensions_and_every_pixel(self):
        png = watermark.png_bytes()
        self.assertEqual(png[:8], b"\x89PNG\r\n\x1a\n")
        chunks = {}
        offset = 8
        while offset < len(png):
            length = struct.unpack_from(">I", png, offset)[0]
            kind = png[offset + 4:offset + 8]
            data = png[offset + 8:offset + 8 + length]
            crc = struct.unpack_from(">I", png, offset + 8 + length)[0]
            self.assertEqual(crc, zlib.crc32(kind + data))
            self.assertNotIn(kind, chunks)
            chunks[kind] = data
            offset += length + 12
        self.assertEqual(offset, len(png))
        self.assertEqual(list(chunks), [b"IHDR", b"IDAT", b"IEND"])
        self.assertEqual(struct.unpack(">IIBBBBB", chunks[b"IHDR"]), (64, 32, 8, 6, 0, 0, 0))
        pixels = zlib.decompress(chunks[b"IDAT"])
        counts = {0: 0, 128: 0, 255: 0}
        for y in range(32):
            self.assertEqual(pixels[y * 257], 0)
            for x in range(64):
                p = y * 257 + 1 + x * 4
                rgba = tuple(pixels[p:p + 4])
                counts[rgba[3]] += 1
                # A second declarative description guards generator geometry independently.
                expected = (25, 230, 55, 0)
                for rect, color in [
                    ((4, 4, 20, 28), (240, 30, 40, 255)),
                    ((24, 4, 44, 28), (25, 45, 240, 128)),
                    ((48, 4, 60, 16), (25, 230, 55, 255)),
                    ((48, 20, 60, 28), (25, 230, 55, 128)),
                ]:
                    left, top, right, bottom = rect
                    if left <= x < right and top <= y < bottom:
                        expected = color
                self.assertEqual(rgba, expected)
        self.assertEqual(counts, {0: 944, 128: 576, 255: 528})

    def test_numeric_alpha_and_free_space_placement(self):
        background = bytes([215, 190, 35]) * (320 * 240)
        candidate = watermark.composite(background)
        opaque = watermark.composite(background, opaque=True)
        shifted = watermark.composite(background, dx=8)

        def pixel(frame, x, y):
            p = (y * 320 + x) * 3
            return tuple(frame[p:p + 3])

        self.assertEqual((round(320 * .2), round(64 * 32 / 64)), (64, 32))
        self.assertEqual((round((320 - 64) * .75), round((240 - 32) * .75)), (192, 156))
        self.assertEqual(pixel(candidate, 198, 162), (240, 30, 40))
        self.assertEqual(pixel(candidate, 218, 162), (120, 117, 138))
        self.assertEqual(pixel(candidate, 242, 178), (120, 210, 45))
        self.assertEqual(pixel(candidate, 242, 174), (215, 190, 35))
        self.assertEqual(pixel(opaque, 242, 174), (25, 230, 55))
        self.assertEqual(pixel(opaque, 218, 162), (25, 45, 240))
        self.assertEqual(pixel(shifted, 198, 162), (215, 190, 35))
        for y in range(240):
            for x in range(320):
                if not (192 <= x < 256 and 156 <= y < 188):
                    self.assertEqual(pixel(candidate, x, y), (215, 190, 35))
        self.assertEqual(background, bytes([215, 190, 35]) * (320 * 240))

    def test_shipped_pack_matches_pins_and_refuses_overwrite(self):
        watermark.verify()
        for directory in (watermark.ASSETS, watermark.BASE):
            with self.assertRaisesRegex(ValueError, "never overwrite pins"):
                watermark.generate(directory)


if __name__ == "__main__":
    unittest.main()
