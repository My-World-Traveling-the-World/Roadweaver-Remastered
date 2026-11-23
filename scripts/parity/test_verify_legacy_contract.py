"""用独立临时目录验证契约工具确实会拒绝删资源、改API、漏注册和语言键。"""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from verify_legacy_contract import tokens_digest, verify


class LegacyContractTest(unittest.TestCase):
    def test_tokens_ignore_comments_but_preserve_url_strings_and_api_signature(self):
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory) / "a.java"
            p.write_text('class A { String url="https://x"; int value(); }', encoding="utf-8")
            original = tokens_digest(p)
            p.write_text('/*中文职责*/ class A { String url="https://x"; /*why*/ int value(); }', encoding="utf-8")
            self.assertEqual(original,tokens_digest(p))
            p.write_text('class A { String url="https://y"; long value(); }', encoding="utf-8")
            self.assertNotEqual(original,tokens_digest(p))

    def test_missing_and_modified_contracts_fail_instead_of_being_silently_skipped(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            (root/"Api.java").write_text("interface Api { int value(); }",encoding="utf-8")
            (root/"resource").write_bytes(b"template")
            (root/"calls").write_text("register(KEEP)",encoding="utf-8")
            (root/"lang.json").write_text(json.dumps({"kept":"保留"}),encoding="utf-8")
            baseline={"apiTokenSha256":{"Api.java":tokens_digest(root/"Api.java")},
                      "resourceSha256":{"resource":hashlib.sha256(b"template").hexdigest()},
                      "registrations":{"calls":{"pattern":r"register\((\w+)\)","values":["KEEP"]}},
                      "languages":{"lang.json":{"keys":["kept","retired"],"retiredAlgorithmOptions":["retired"]}}}
            self.assertEqual([],verify(root,baseline))
            (root/"resource").unlink()
            (root/"Api.java").write_text("interface Api { long value(); }",encoding="utf-8")
            (root/"calls").write_text("",encoding="utf-8")
            (root/"lang.json").write_text("{}",encoding="utf-8")
            self.assertEqual(4,len(verify(root,baseline)))

    def test_approved_resource_revision_rejects_accidental_revert(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            metadata = root / "fabric.mod.json"
            old, new = b"embedded", b"standalone"
            baseline = {
                "apiTokenSha256": {}, "registrations": {}, "languages": {},
                "resourceSha256": {metadata.name: hashlib.sha256(old).hexdigest()},
                "approvedResourceChanges": {
                    metadata.name: {"sha256": hashlib.sha256(new).hexdigest()}
                },
            }
            # 记录允许的新元数据后，恢复旧的内嵌状态也必须失败。
            metadata.write_bytes(new)
            self.assertEqual([], verify(root, baseline))
            metadata.write_bytes(old)
            self.assertEqual(1, len(verify(root, baseline)))


if __name__ == "__main__":
    unittest.main()
