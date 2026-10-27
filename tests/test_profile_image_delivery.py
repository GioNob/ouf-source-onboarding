import importlib.util,pathlib,tempfile,unittest,zipfile
spec=importlib.util.spec_from_file_location('stage',pathlib.Path(__file__).parents[1]/'scripts/stage_profile_recovery_image.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class Tests(unittest.TestCase):
    def test_parts_reassemble_identical_bytes(self):
        with tempfile.TemporaryDirectory()as folder:
            root=pathlib.Path(folder);source=root/'original';raw=bytes(range(256))*31;source.write_bytes(raw);out=root/'parts';out.mkdir();rows=m.split(source,out,1000)
            self.assertEqual(b''.join((out/r['name']).read_bytes()for r in rows),raw);self.assertEqual(sum(r['bytes']for r in rows),len(raw));self.assertTrue(all(m.file_hash(out/r['name'])==r['sha256']for r in rows))
    def test_wrong_artifact_refused_before_creating_delivery(self):
        with tempfile.TemporaryDirectory()as folder:
            root=pathlib.Path(folder);source=root/'wrong';source.write_bytes(b'wrong')
            with self.assertRaises(m.Stop):m.prepare(source,root/'delivery',root)
            self.assertFalse((root/'delivery').exists())
    def test_flyway_line_endings_do_not_change_checksum(self):
        self.assertEqual(m.checksum(b'one\ntwo\n'),m.checksum(b'\xef\xbb\xbfone\r\ntwo\r\n'));self.assertNotEqual(m.checksum(b'one\n'),m.checksum(b'two\n'))
    def test_missing_migrations_cannot_be_adopted(self):
        with tempfile.TemporaryDirectory()as folder:
            p=pathlib.Path(folder)/'application.jar'
            with zipfile.ZipFile(p,'w')as z:z.writestr('BOOT-INF/classes/db/migration/V36__managed_profile_recovery_sessions.sql','select 1;')
            with self.assertRaises(m.Stop):m.jar_facts(p,[])
if __name__=='__main__':unittest.main()
