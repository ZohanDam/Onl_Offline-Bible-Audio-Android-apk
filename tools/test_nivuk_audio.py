"""NIVUK source identity tests; no network or audio downloads."""
import unittest
from import_nivuk_audio import extract_audio, validate_url


class NivukAudioTests(unittest.TestCase):
    url = "https://stream.biblegateway.com/bibles/32/nivuk-suchet/Matt.1.b3953aa5a8b96bdc5a9de4c35290cc87.mp3"

    def page(self, url=None, recording="nivuk-suchet"):
        return f'<select name="recording"><option selected value="{recording}">NIVUK by David Suchet</option></select><audio src="{url or self.url}"></audio>'

    def test_suchet_chapter(self):
        self.assertEqual(self.url, extract_audio(self.page(), "Matt", 1))

    def test_different_recording_is_rejected(self):
        for recording in ("niv-mclean", "nivuk-south-asian"):
            with self.assertRaises(ValueError):
                extract_audio(self.page(recording=recording), "Matt", 1)

    def test_wrong_chapter_and_host_and_path_are_rejected(self):
        for url in (self.url.replace("Matt.1.", "Matt.2."),
                    self.url.replace("stream.biblegateway.com", "example.com"),
                    self.url.replace("nivuk-suchet", "niv-mclean"), self.url + "?token=example"):
            with self.assertRaises(ValueError):
                validate_url(url, "Matt", 1)

    def test_missing_source_is_rejected(self):
        with self.assertRaises(ValueError):
            extract_audio("<html/>", "Matt", 1)


if __name__ == "__main__":
    unittest.main()
