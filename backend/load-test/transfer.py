import argparse
import gzip
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

from assets import load_fixture, run_lock

SCRIPT_DIR = Path(__file__).resolve().parent
MAX_BYTES = 1024 * 1024


def openssl(*args, data=None):
    try:
        return subprocess.run(['openssl', *args], input=data, check=True, capture_output=True).stdout
    except subprocess.CalledProcessError:
        # Decrypt can return partial plaintext on failure. Never print its output.
        raise ValueError('OpenSSL failed. Check OpenSSL 3, the key and the complete copied text') from None


def require_openssl():
    if not openssl('version').startswith(b'OpenSSL 3.'):
        raise ValueError('OpenSSL 3 is required on both computers')


def create_key(directory):
    require_openssl()
    os.umask(0o077)
    directory.mkdir(parents=True, exist_ok=False)
    openssl('req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '7',
            '-subj', '/CN=harudle-load-test', '-keyout', str(directory / 'private.pem'),
            '-out', str(directory / 'recipient.pem'))
    return (directory / 'recipient.pem').read_text()


def export_members(directory, certificate):
    require_openssl()
    certificate = certificate.replace('\r\n', '\n').strip() + '\n'
    if not re.fullmatch(r'-----BEGIN CERTIFICATE-----\n[A-Za-z0-9+/=\s]+-----END CERTIFICATE-----\n', certificate):
        raise ValueError('Paste only the complete public certificate, never private.pem')
    with run_lock(directory):
        if not (directory / '.ready').exists() or (directory / 'cleanup.json').exists():
            raise ValueError('Data preparation must finish and cleanup must not have started')
        fixture = load_fixture(directory)
        recipient = directory / 'recipient.pem'
        if recipient.exists() and recipient.read_text() != certificate:
            raise ValueError('This run already has a recipient. Use the original local key')
        with tempfile.TemporaryDirectory(dir=directory) as temporary:
            candidate = Path(temporary) / 'recipient.pem'
            candidate.write_text(certificate)
            encrypted = openssl('cms', '-encrypt', '-binary', '-aes-256-gcm', '-outform', 'PEM',
                                '-recip', str(candidate), '-keyopt', 'rsa_padding_mode:oaep',
                                '-keyopt', 'rsa_oaep_md:sha256',
                                data=gzip.compress(json.dumps(fixture, ensure_ascii=False).encode()))
            candidate.replace(recipient)
        # Persist the cleanup guard before the encrypted members can leave this machine.
        with (directory / '.exported').open('w') as marker:
            marker.write(fixture['runId'])
            marker.flush()
            os.fsync(marker.fileno())
        descriptor = os.open(directory, os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
        return encrypted.decode('ascii')


def receive_members(directory, encrypted):
    require_openssl()
    if len(encrypted) > MAX_BYTES:
        raise ValueError('The copied text is too large')
    with run_lock(directory):
        if (directory / 'members.json').exists() or (directory / '.started').exists():
            raise ValueError('This run already has members. Use a new run for another test')
        compressed = openssl('cms', '-decrypt', '-binary', '-inform', 'PEM',
                             '-recip', str(directory / 'recipient.pem'),
                             '-inkey', str(directory / 'private.pem'), data=encrypted)
        with gzip.GzipFile(fileobj=io.BytesIO(compressed)) as stream:
            data = stream.read(MAX_BYTES + 1)
        if len(data) > MAX_BYTES:
            raise ValueError('The member file is too large')
        # Validate before publishing members.json; a broken copy must not leave usable data.
        with tempfile.TemporaryDirectory(dir=directory) as temporary:
            path = Path(temporary) / 'members.json'
            path.write_bytes(data)
            fixture = load_fixture(Path(temporary))
            if fixture['baseUrl'] != 'https://dev.harudle.com':
                raise ValueError('Exported members must target https://dev.harudle.com')
            path.replace(directory / 'members.json')


def main():
    parser = argparse.ArgumentParser(description='Copy encrypted members through Session Manager, without local AWS CLI')
    parser.add_argument('action', choices=['keygen', 'receive'])
    parser.add_argument('run', help='The same run name used on EC2')
    args = parser.parse_args()
    if not re.fullmatch(r'[a-zA-Z0-9_-]+', args.run):
        parser.error('Use only letters, digits, hyphens and underscores for the run name')
    directory = SCRIPT_DIR / 'runs' / args.run
    try:
        if args.action == 'keygen':
            print(create_key(directory), end='')
        else:
            receive_members(directory, sys.stdin.buffer.read(MAX_BYTES + 1))
            print(f'Restored members.json for {args.run}. Run k6 locally.')
    except (OSError, ValueError, KeyError, TypeError, EOFError) as error:
        parser.error(str(error))


if __name__ == '__main__':
    main()
