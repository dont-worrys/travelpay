import importlib.util
from pathlib import Path
import unittest

path = Path(__file__).parents[1] / "scripts" / "mount-data-volumes.py"
spec = importlib.util.spec_from_file_location("data_storage", path)
storage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(storage)

VOLUME = {"id": "vol-0123456789abcdef0", "size_bytes": 32212254720, "mount": "/srv/travelpay/kafka"}


def disk(**changes):
    return dict({"path": "/dev/nvme2n1", "serial": "vol0123456789abcdef0", "size": 32212254720,
                 "type": "disk", "fstype": None, "mountpoints": [None]}, **changes)


class DataStorageTest(unittest.TestCase):
    def test_selects_volume_by_ebs_identity_not_device_order(self):
        other = disk(path="/dev/nvme1n1", serial="vol11111111111111111")
        self.assertEqual(storage.select_device([other, disk()], VOLUME)["path"], "/dev/nvme2n1")

    def test_missing_or_ambiguous_identity_is_rejected(self):
        for devices in [[], [disk(), disk(path="/dev/nvme3n1")]]:
            with self.subTest(devices=devices), self.assertRaises(storage.StorageError):
                storage.select_device(devices, VOLUME)

    def test_never_formats_partitioned_mounted_or_unexpected_volume(self):
        for changed in [dict(children=[{"path": "/dev/nvme2n1p1"}]), dict(mountpoints=["/"]),
                        dict(fstype="xfs"), dict(size=17179869184), dict(type="part")]:
            with self.subTest(changed=changed), self.assertRaises(storage.StorageError):
                storage.select_device([disk(**changed)], VOLUME)

    def test_existing_ext4_is_reused_and_unknown_signature_is_rejected(self):
        self.assertFalse(storage.needs_format(disk(fstype="ext4"), [{"type": "ext4"}]))
        self.assertTrue(storage.needs_format(disk(), []))
        with self.assertRaises(storage.StorageError):
            storage.needs_format(disk(), [{"type": "gpt"}])


if __name__ == "__main__":
    unittest.main()
