"""Synthetic Java-only false-positive coverage; no credential values or source writes."""
import unittest
from scripts.test_codex_work_checkpoint import CP


class JsonFieldReadTest(unittest.TestCase):
    key = 'api' + 'Key'
    word = 'to' + 'ken'

    def scan(self, text, path='main/java/Example.java'):
        CP.secret_free(text.encode(), path)

    def test_jackson_path_as_text_assignment_is_literal_free(self):
        # Jackson 필드 읽기는 파싱된 런타임 값을 대입 — 리터럴 자격증명이 아니다.
        self.scan('String ' + self.word + '=grant.path("' + self.word + '").asText();'
                  'assertTrue(' + self.word + '.matches("[a-f0-9]{64}"));')
        self.scan('var ' + self.word + ' = node.path("access_' + self.word + '").asText();')
        self.scan('String ' + self.key + ' = doc.get("id").asLong();')
        self.scan(self.word + '=body.get("k").asBoolean();')

    def test_rhs_concat_and_literal_remain_strict(self):
        for rhs in ('grant.path("k").asText() + suffix;',
                    'grant.path("k");',
                    'grant.read("k").asText();',
                    'body.get("session");',
                    '"synthetic";'):
            with self.subTest(rhs=rhs), self.assertRaises(CP.CheckpointError):
                self.scan(self.word + ' = ' + rhs)

    def test_comments_and_other_languages_remain_strict(self):
        fixture = 'String ' + self.word + '=grant.path("k").asText();'
        for text in ('// ' + fixture, '/* ' + fixture + ' */', '"' + fixture + '"'):
            with self.subTest(length=len(text)), self.assertRaises(CP.CheckpointError):
                self.scan(text)
        with self.assertRaises(CP.CheckpointError):
            self.scan(fixture, 'docs/note.md')

    def test_following_secret_still_scanned(self):
        with self.assertRaises(CP.CheckpointError):
            self.scan('String ' + self.word + '=grant.path("k").asText();\n'
                      + self.key + '="synthetic";')


if __name__ == '__main__':
    unittest.main()
