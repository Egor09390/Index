"""Rebuild the signed APK, verifying both input and output byte for byte."""
from base64 import b64decode
from hashlib import sha256
import json
from pathlib import Path
import sys
import zlib

source = Path(sys.argv[1]).read_bytes()
if sha256(source).hexdigest() != '9f9a4b61646f3cf59c995d4da33e06f12f21fc9c3f38a2a14b5b699f3f700919':
    sys.exit("Unexpected original APK checksum")
patch = "".join(path.read_text() for path in sorted(Path(__file__).parent.glob("patch-*")))
result = bytearray()
for op in json.loads(zlib.decompress(b64decode(patch))):
    if isinstance(op, str):
        result.extend(b64decode(op))
    else:
        offset, size = op
        result.extend(source[offset:offset + size])
if sha256(result).hexdigest() != '2bb95de9de008b49f5bbea2d417cb858abdadea5f55f6b5fec3f6b14b1f960dc':
    sys.exit("Unexpected signed APK checksum")
Path(sys.argv[2]).write_bytes(result)
