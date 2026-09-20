#!/usr/bin/env python3
"""Aggregate existing APISIX access timings from stdin; never emit raw log lines."""
import json
import math
import re
import sys
from collections import defaultdict

# APISIX default combined format, optionally prefixed by docker --timestamps.
ACCESS = re.compile(r'"(?:POST|GET) (?P<path>\S+) HTTP/[^" ]+" (?P<status>\d{3}) \d+ (?P<total>\d+(?:\.\d+)?) "[^"]*" "[^"]*" (?P<upstream>.*?) "[^"\n]*" "[^"\n]*"$')

def summarize(lines):
    groups = defaultdict(list)
    for line in lines:
        match = ACCESS.search(line.strip())
        if not match:
            continue
        path = match['path'].split('?', 1)[0]
        if path == '/mcp':
            category = 'mcp'
        elif path.startswith('/internal/capabilities/v1/execute'):
            category = 'internal_execute'
        elif path.startswith('/trusted-human/authorization/api/proposals/'):
            category = 'ths_decision' if path.endswith(('/confirm', '/reject')) else 'ths_read'
        else:
            continue
        # Retry/multi-upstream timings have a different shape: omit rather than guess.
        parts = match['upstream'].split()
        upstream = None
        if len(parts) == 3 and re.fullmatch(r'\d+(?:\.\d+)?', parts[2]):
            upstream = float(parts[2]) * 1000
        groups[(category, match['status'])].append((float(match['total']) * 1000, upstream))
    def stats(values):
        ordered = sorted(values)
        if not ordered:
            return None
        return {'count': len(ordered), 'p50_ms': ordered[math.ceil(len(ordered) * .5)-1], 'p95_ms': ordered[math.ceil(len(ordered) * .95)-1], 'max_ms': ordered[-1]}
    return [{'category': category, 'http_status': status,
             'request': stats([x[0] for x in values]),
             'upstream': stats([x[1] for x in values if x[1] is not None])}
            for (category, status), values in sorted(groups.items())]

if __name__ == '__main__':
    result = summarize(sys.stdin)
    print(json.dumps({'groups': result, 'note': 'HTTP timings only; MCP HTTP 200 can contain a tool error. Nested request durations must not be added.'}, indent=2))
