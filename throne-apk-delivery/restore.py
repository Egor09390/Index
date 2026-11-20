"""Rebuild the signed APK, verifying both input and output byte for byte."""
from base64 import b64decode
from hashlib import sha256
import json
from pathlib import Path
import sys
import zlib

source = Path(sys.argv[1]).read_bytes()
if sha256(source).hexdigest() != '524b1e9865e4cfefdb582c295d53fb5b256a1f96364c37d83799db9181e3c022':
    sys.exit("Unexpected original APK checksum")
patch = "".join(path.read_text() for path in sorted(Path(__file__).parent.glob("patch-*")))
result = bytearray()
for op in json.loads(zlib.decompress(b64decode(patch))):
    if isinstance(op, str):
        result.extend(b64decode(op))
    else:
        offset, size = op
        result.extend(source[offset:offset + size])
if sha256(result).hexdigest() != '3eb72ee12ed84e9f56c7e8327aa84353f6df410ed2e0cd37c07b71a7ba867bfe':
    sys.exit("Unexpected signed APK checksum")
Path(sys.argv[2]).write_bytes(result)
