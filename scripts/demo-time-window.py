# -*- coding: utf-8 -*-
"""Open or close the demo subscription policy's time window.

    python scripts/demo-time-window.py open    # any day, any hour
    python scripts/demo-time-window.py office  # MON-FRI 08:00-18:00 Asia/Bangkok

Why this exists as a script rather than a decision baked into the seed:

`finance-subscription` carries the time predicate that proves FR-5.4 — a refusal
that names the policy and the reason. Leaving it at office hours means the whole
demo refuses every evening and every weekend, which is most of the time somebody
is likely to be looking at it, and the refusal they get says nothing about row
filtering or masking. Leaving it permanently open means the time predicate is
never demonstrated at all. Neither default is right, so the window is a switch
and the handoff says which position it is in.

Run with the project .env sourced; needs the backend up on :8080.
"""
import json
import os
import sys
import urllib.request

BASE = 'http://localhost:8080/api/v1'
POLICY = 'b594579f-a825-42bc-9dac-64de9b279d03'

WINDOWS = {
    'open': {'days': ['MON-FRI', 'SAT', 'SUN'], 'from': '00:00', 'to': '23:59',
             'tz': 'Asia/Bangkok'},
    'office': {'days': ['MON-FRI'], 'from': '08:00', 'to': '18:00',
               'tz': 'Asia/Bangkok'},
}

REASONS = {
    'open': 'Open the demo window so row filtering and masking can be shown on '
            'any day. The time predicate itself is proven by switching back.',
    'office': 'Close the demo window to office hours so the time predicate is '
              'demonstrable — expect refusals outside MON-FRI 08:00-18:00.',
}


def call(method, path, body=None, tok=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if tok:
        req.add_header('Authorization', 'Bearer ' + tok)
    req.add_header('Content-Type', 'application/json')
    return json.loads(urllib.request.urlopen(req).read().decode())


def main():
    mode = (sys.argv[1] if len(sys.argv) > 1 else '').lower()
    if mode not in WINDOWS:
        sys.exit(__doc__)

    tok = call('POST', '/auth/login', {
        'username': 'admin',
        'password': os.environ['IDENTITY_BOOTSTRAP_ADMIN_PASSWORD'],
    })['accessToken']

    current = call('GET', '/policies/' + POLICY, tok=tok)
    doc = current['document']
    doc['subject']['time']['windows'] = [WINDOWS[mode]]

    # The body is the Policy document itself; `version` and `reason` are query
    # parameters. Sending {document, expectedVersion, reason} as a body is a 400.
    query = '?version=%d&reason=%s' % (
        current['version'], urllib.parse.quote(REASONS[mode]))
    out = call('PUT', '/policies/' + POLICY + query, doc, tok=tok)

    print('%s -> version %s' % (mode, out.get('version')))
    print(json.dumps(out['document']['subject']['time'], indent=1))


if __name__ == '__main__':
    import urllib.parse
    main()
