import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('latency', Path(__file__).resolve().parents[1] / 'ops/summarize_mcp_latency.py')
latency = importlib.util.module_from_spec(spec)
spec.loader.exec_module(latency)

class LatencyTest(unittest.TestCase):
    def test_real_format_and_nested_exclusion(self):
        line = '2026-09-20T09:15:42Z 172.18.0.8 - - [20/Sep/2026:09:15:42 +0000] api.ouf-lab.it "POST /mcp HTTP/1.1" 200 298 0.042 "-" "openai-mcp/1.0.0 (Codex)" 172.18.0.10:8080 200 0.041 "http://api.ouf-lab.it/mcp" "request-id"'
        out = latency.summarize([line, line.replace('/mcp HTTP', '/internal/capabilities/v1/execute HTTP')])
        self.assertEqual(len(out), 2)
        self.assertEqual(out[1]['request']['p95_ms'], 42)
        self.assertEqual(out[1]['upstream']['p95_ms'], 41)
        self.assertNotIn('request-id', str(out))

    def test_denial_noise_and_no_matches(self):
        line = 'host "POST /mcp HTTP/1.1" 401 251 0.001 "-" "agent" - - - "host" "id"'
        out = latency.summarize(['garbage secret', line])
        self.assertEqual(out[0]['http_status'], '401')
        self.assertIsNone(out[0]['upstream'])
        self.assertEqual(latency.summarize(['unmatched']), [])

if __name__ == '__main__':
    unittest.main()
