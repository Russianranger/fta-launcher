import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from check_auction_count import verify_change


class AuctionCountTests(unittest.TestCase):
    def test_only_getter_changes_and_existing_narrowing_remains_explicit(self):
        before = b'query unchanged\r\nuint32 const count = fields[4].Get<uint32>();\r\n'
        after = b'query unchanged\r\nuint32 const count = static_cast<uint32>(fields[4].Get<uint64>());\r\n'
        verify_change(before, after)
        for bad in (after.replace(b'query unchanged', b'new query'), after.replace(b'\r\n', b'\n'),
                    after.replace(b'fields[4]', b'fields[3]'), after.replace(b'static_cast<uint32>', b'static_cast<uint64>')):
            with self.assertRaises(ValueError): verify_change(before, bad)
        with self.assertRaises(ValueError): verify_change(before + before, after + after)


if __name__ == '__main__':
    unittest.main()
