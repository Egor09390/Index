"""Rebuild the signed APK, verifying both input and output byte for byte."""
from base64 import b64decode
from hashlib import sha256
import json
from pathlib import Path
import sys
import zlib

source = Path(sys.argv[1]).read_bytes()
if sha256(source).hexdigest() != 'd3101388a735b7d343a92faade038f56a0cce311155c80ecc4a132a6ee5212ec':
    sys.exit("Unexpected original APK checksum")
patch = "".join(path.read_text() for path in sorted(Path(__file__).parent.glob("patch-*")))
result = bytearray()
for op in json.loads(zlib.decompress(b64decode(patch))):
    if isinstance(op, str):
        result.extend(b64decode(op))
    else:
        offset, size = op
        result.extend(source[offset:offset + size])
if sha256(result).hexdigest() != 'b5e58e749354a62d53b6d9c17a9a33226d2eeb769c6a7e99c9ccf9b0b2010edc':
    sys.exit("Unexpected signed APK checksum")
Path(sys.argv[2]).write_bytes(result)
