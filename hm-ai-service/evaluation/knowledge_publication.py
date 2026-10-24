"""Read or conditionally publish knowledge through the local AI container; credentials stay inside it."""
import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

MAX_BYTES = 1024 * 1024
SCRIPT = r'''set -eu
test -n "${HMDP_INTERNAL_TOKEN:-}" && test -n "${HMDP_AI_KNOWLEDGE_PUBLISH_TOKEN:-}" || exit 78
if [ "$1" = PUT ]; then
  publication_tmp=$(mktemp /tmp/lifeflash-publication.XXXXXX)
  trap 'rm -f "$publication_tmp"' EXIT HUP INT TERM
  cat > "$publication_tmp"
  wget --quiet --server-response --content-on-error --max-redirect=0 --timeout=15 --tries=1 -O - \
    --header="X-Internal-Token: $HMDP_INTERNAL_TOKEN" \
    --header="X-Knowledge-Publish-Token: $HMDP_AI_KNOWLEDGE_PUBLISH_TOKEN" \
    --header='Content-Type: application/json' --header="If-Match: $2" \
    --method=PUT --body-file="$publication_tmp" http://127.0.0.1:8085/internal/knowledge/publication
else
  exec wget --quiet --server-response --content-on-error --max-redirect=0 --timeout=15 --tries=1 -O - \
    --header="X-Internal-Token: $HMDP_INTERNAL_TOKEN" \
    --header="X-Knowledge-Publish-Token: $HMDP_AI_KNOWLEDGE_PUBLISH_TOKEN" \
    http://127.0.0.1:8085/internal/knowledge/publication
fi
'''


def request(container, content=None, etag=None):
    try:
        result = subprocess.run(['docker', 'exec', '-i', container, 'sh', '-c', SCRIPT, 'knowledge-publisher',
                                 'GET' if content is None else 'PUT', etag or ''], input=content or b'',
                                capture_output=True, timeout=25)
    except subprocess.TimeoutExpired:
        # PUT确认超时不能直接重试；先读取当前ETag和修订确认事务是否已生效。
        return {'ok': False, 'code': 'CONFIRMATION_UNAVAILABLE_READ_CURRENT_BEFORE_RETRY'}, 1
    if result.returncode == 78:
        return {'ok': False, 'code': 'PUBLISH_CREDENTIALS_NOT_CONFIGURED'}, 1
    statuses = re.findall(r'HTTP/\S+\s+(\d{3})', result.stderr.decode('utf-8', errors='replace'))
    status = int(statuses[-1]) if statuses else None
    try:
        body = json.loads(result.stdout)
    except (ValueError, UnicodeDecodeError):
        return {'ok': False, 'status': status, 'code': 'PUBLICATION_RESPONSE_UNAVAILABLE'}, 1
    headers = re.findall(r'^\s*ETag:\s*(.+?)\s*$', result.stderr.decode('utf-8', errors='replace'), re.I | re.M)
    ok = status == 200 and result.returncode == 0
    return {'ok': ok, 'status': status, 'etag': headers[-1] if headers else None, 'result': body}, 0 if ok else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['current', 'publish'])
    parser.add_argument('--container', default='hm-dianping-ai-service-1')
    parser.add_argument('--file', type=Path)
    parser.add_argument('--if-match', help='Exact generation-revision from current; use 0 only for the first publication')
    args = parser.parse_args()
    content = None
    expected = None
    if args.action == 'publish':
        if args.file is None or args.if_match is None:
            parser.error('publish requires --file and --if-match')
        value = args.if_match.strip('"')
        if not re.fullmatch(r'(?:0|[1-9][0-9]{0,18}-[a-f0-9]{64})', value):
            parser.error('Use the exact ETag from current; wildcard publication is forbidden')
        expected = '"' + value + '"'
        with args.file.open('rb') as stream:
            content = stream.read(MAX_BYTES + 1)
        if not content or len(content) > MAX_BYTES:
            parser.error('Publication must be nonempty and at most 1 MiB')
    elif args.file is not None or args.if_match is not None:
        parser.error('current does not accept publication arguments')
    record, code = request(args.container, content, expected)
    print(json.dumps(record, ensure_ascii=False, indent=2))
    return code


if __name__ == '__main__':
    sys.exit(main())
