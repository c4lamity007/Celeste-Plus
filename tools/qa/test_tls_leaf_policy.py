from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

spec = importlib.util.spec_from_file_location("tls_leaf_policy", Path(__file__).resolve().parents[1] / "packaging/tls_leaf_policy.py")
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)


class PinReviewTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        cls.now = datetime.now(timezone.utc)

    def der(self, host=policy.HOST, days=30):
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, host)])
        cert = x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(self.key.public_key()).serial_number(1).not_valid_before(self.now - timedelta(days=1)).not_valid_after(self.now + timedelta(days=days)).add_extension(x509.SubjectAlternativeName([x509.DNSName(host)]), False).sign(self.key, hashes.SHA256())
        return cert.public_bytes(serialization.Encoding.DER)

    def test_wrong_origin_or_expiring_certificate_rejected(self):
        for kwargs in ({"host": "attacker.example"}, {"days": 2}):
            with self.assertRaises(ValueError):
                policy.certificate(self.der(**kwargs), now=self.now)

    def test_review_is_bound_to_hash_freshness_and_exact_der(self):
        entry = policy.certificate(self.der(), now=self.now)
        value = {"schema": "celeste-tls-leaf-review-1", "origin": policy.ORIGIN,
                 "observed_utc": self.now.isoformat(), "certificates": [entry]}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "pins.json"
            path.write_text(json.dumps(value))
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            self.assertEqual([entry["sha256"]], policy.reviewed(path, digest, now=self.now))
            with self.assertRaises(ValueError):
                policy.reviewed(path, "0" * 64, now=self.now)
            with self.assertRaises(ValueError):
                policy.reviewed(path, digest, now=self.now + timedelta(days=2))
            value["certificates"][0]["sha256"] = "0" * 64
            path.write_text(json.dumps(value))
            with self.assertRaises(ValueError):
                policy.reviewed(path, hashlib.sha256(path.read_bytes()).hexdigest(), now=self.now)


if __name__ == "__main__":
    unittest.main()
