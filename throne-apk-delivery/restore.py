"""Rebuild the signed APK, verifying both input and output byte for byte."""
from base64 import b64decode
from hashlib import sha256
import json
from pathlib import Path
import sys
import zlib

source = Path(sys.argv[1]).read_bytes()
if sha256(source).hexdigest() != '968541310b04d475a70ae0675f79dafab852feb6321ef17718f0af82af0db432':
    sys.exit("Unexpected original APK checksum")
patch = "".join(path.read_text() for path in sorted(Path(__file__).parent.glob("patch-*")))
result = bytearray()
for op in json.loads(zlib.decompress(b64decode(patch))):
    if isinstance(op, str):
        result.extend(b64decode(op))
    else:
        offset, size = op
        result.extend(source[offset:offset + size])
if sha256(result).hexdigest() != '180582c9ef26801b6e7c3d016fb0935e31be5ac09984a8c2374c9ce2a6df5f90':
    sys.exit("Unexpected signed APK checksum")
Path(sys.argv[2]).write_bytes(result)
