import importlib.util
from pathlib import Path
import tempfile
import unittest

path = Path(__file__).parents[1] / "scripts" / "prepare-data.py"
spec = importlib.util.spec_from_file_location("data_preparation", path)
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class DataPreparationTest(unittest.TestCase):
    def test_secret_cannot_inject_redis_directives(self):
        for invalid in ["short", "a" * 40 + "\nprotected-mode no", 'a' * 40 + '"']:
            with self.subTest(invalid=invalid), self.assertRaises(prepare.PreparationError):
                prepare.redis_config(invalid)
        config = prepare.redis_config("a" * 64)
        self.assertIn("maxmemory-policy noeviction\n", config)
        self.assertIn("requirepass " + "a" * 64 + "\n", config)

    def test_kafka_cluster_id_survives_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first = prepare.cluster_id(root)
            self.assertEqual(prepare.cluster_id(root), first)
            self.assertRegex(first, r"^[A-Za-z0-9_-]{22}$")

    def test_cluster_id_is_recovered_from_existing_kafka_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "data").mkdir()
            (root / "data/meta.properties").write_text("cluster.id=YpPuKnjuGLYUqVLlmxun8Q\nnode.id=1\n")
            self.assertEqual(prepare.cluster_id(root), "YpPuKnjuGLYUqVLlmxun8Q")

    def test_conflicting_cluster_ids_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "data").mkdir()
            (root / "cluster.id").write_text("YpPuKnjuGLYUqVLlmxun8Q\n")
            (root / "data/meta.properties").write_text("cluster.id=AAAAAAAAAAAAAAAAAAAAAA\n")
            with self.assertRaises(prepare.PreparationError):
                prepare.cluster_id(root)


if __name__ == "__main__":
    unittest.main()
