#!/usr/bin/env python3
"""Build EMA's compact offline dictionary from the unmodified WordNet 3.0 ZIP.

Source: nltk/nltk_data, packages/corpora/wordnet.zip at Git blob
3c68cd207844f071a5206f7fbed23e5c607ec02f. The WordNet license in the archive
must be included alongside the generated files. Output is sorted, deterministic
UTF-8 TSV, grouped by initial character and gzip-compressed with a zero mtime.
Usage: python3 tools/build_wordnet_dictionary.py /path/to/wordnet.zip
"""
import gzip
import hashlib
import io
import pathlib
import sys
import zipfile
from collections import defaultdict

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / "app/src/main/assets/wordnet"
# SHA-256 identifies the upstream ZIP, not just its filename.
EXPECTED_SHA256 = "cbda5ea6eef7f36a97a43d4a75f85e07fccbb4f23657d27b4ccbc93e2646ab59"


def build(source: pathlib.Path) -> None:
    archive = source.read_bytes()
    digest = hashlib.sha256(archive).hexdigest()
    if digest != EXPECTED_SHA256:
        raise SystemExit(f"Unexpected WordNet ZIP SHA-256: {digest}")
    rows = defaultdict(list)
    with zipfile.ZipFile(io.BytesIO(archive)) as z:
        for suffix, part in (("noun", "noun"), ("verb", "verb"), ("adj", "adjective"), ("adv", "adverb")):
            definitions = {}
            for line in z.read(f"wordnet/data.{suffix}").decode("utf-8").splitlines():
                if not line or not line[0].isdigit() or " | " not in line:
                    continue
                header, gloss = line.split(" | ", 1)
                definitions[header.split()[0]] = gloss.strip().replace("\t", " ")
            for line in z.read(f"wordnet/index.{suffix}").decode("utf-8").splitlines():
                if not line or line.startswith(" "):
                    continue
                fields = line.split()
                lemma, count, pointers = fields[0], int(fields[2]), int(fields[3])
                offsets = fields[6 + pointers:6 + pointers + count]
                senses = [definitions[offset] for offset in offsets if offset in definitions]
                if len(senses) != count:
                    raise ValueError(f"Missing WordNet sense: {lemma} {part}")
                key = lemma.replace("_", " ")
                first = key[0] if key[0] in "abcdefghijklmnopqrstuvwxyz" else "other"
                rows[first].append("\t".join([key, part, *senses]))
        exceptions = []
        for suffix, part in (("noun", "noun"), ("verb", "verb"), ("adj", "adjective"), ("adv", "adverb")):
            for line in z.read(f"wordnet/{suffix}.exc").decode("utf-8").splitlines():
                words = line.split()
                if len(words) > 1:
                    exceptions.append("\t".join([words[0].replace("_", " "), part, *[w.replace("_", " ") for w in words[1:]]]))
        OUT.mkdir(parents=True, exist_ok=True)
        (OUT / "LICENSE.txt").write_bytes(z.read("wordnet/LICENSE"))
        with (OUT / "exceptions.tsv.gz").open("wb") as target:
            with gzip.GzipFile(filename="", mode="wb", fileobj=target, mtime=0) as gz:
                gz.write(("\n".join(sorted(exceptions)) + "\n").encode("utf-8"))
    for bucket, lines in rows.items():
        payload = ("\n".join(sorted(lines)) + "\n").encode("utf-8")
        with (OUT / f"{bucket}.tsv.gz").open("wb") as target:
            with gzip.GzipFile(filename="", mode="wb", fileobj=target, mtime=0) as gz:
                gz.write(payload)
    print(f"Generated {sum(len(v) for v in rows.values()):,} entries in {len(rows)} buckets.")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    build(pathlib.Path(sys.argv[1]))
