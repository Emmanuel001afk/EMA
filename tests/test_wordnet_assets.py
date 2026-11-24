"""Offline dictionary packaging and source-data sanity checks."""
import gzip
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets/wordnet"


class WordNetAssetsTest(unittest.TestCase):
    def test_license_and_all_buckets_are_packaged(self):
        license_text = (ASSETS / "LICENSE.txt").read_text()
        self.assertIn("WordNet 3.0 Copyright 2006 by Princeton University", license_text)
        for name in "abcdefghijklmnopqrstuvwxyz":
            self.assertTrue((ASSETS / f"{name}.tsv.gz").is_file(), name)
        self.assertTrue((ASSETS / "other.tsv.gz").is_file())
        with gzip.open(ASSETS / "exceptions.tsv.gz", "rt", encoding="utf-8") as stream:
            self.assertIn("children\tnoun\tchild\n", stream.read())

    def test_entries_cover_multiple_senses_and_real_world_words(self):
        cases = {"b": ("bank", "financial institution"), "p": ("photosynthesis", "photosynthesis"),
                 "r": ("resilience", "resilience"), "l": ("love", "love")}
        for bucket, (word, expected) in cases.items():
            with gzip.open(ASSETS / f"{bucket}.tsv.gz", "rt", encoding="utf-8") as stream:
                matches = [line for line in stream if line.startswith(word + "\t")]
            self.assertTrue(matches, word)
            self.assertIn(expected, " ".join(matches).lower())
        with gzip.open(ASSETS / "b.tsv.gz", "rt", encoding="utf-8") as stream:
            bank = [line for line in stream if line.startswith("bank\tnoun\t")][0]
        self.assertGreater(len(bank.rstrip("\n").split("\t")), 4)


if __name__ == "__main__":
    unittest.main()
