#!/usr/bin/env python3
"""List and download EmbracePlus data from the Empatica data bucket.

Credentials come from %USERPROFILE%\\.thesis\\empatica.env (see save_empatica_keys.ps1),
never from this repository, and are never printed.

    python scripts/empatica_s3.py ls [prefix]              # list one level under a prefix
    python scripts/empatica_s3.py get <prefix> <out_dir>   # download every object under a prefix
"""
import re
import sys
from pathlib import Path
from urllib.parse import urlparse

import boto3


def load_env():
    env = {}
    path = Path.home() / ".thesis" / "empatica.env"
    for line in path.read_text().splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            env[key.strip()] = value.strip()
    return env


def client_and_bucket(env):
    raw = env["EMPATICA_BUCKET"]
    region = env.get("EMPATICA_REGION") or None
    prefix = env.get("EMPATICA_PREFIX", "").strip("/")
    if raw.startswith("s3://"):
        parsed = urlparse(raw)
        bucket, path = parsed.netloc, parsed.path.strip("/")
    elif raw.startswith("http"):
        parsed = urlparse(raw)
        host = parsed.netloc
        m = re.match(r"(.+)\.s3[.-]([a-z0-9-]+)\.amazonaws\.com", host)
        if m:
            bucket, region = m.group(1), region or m.group(2)
        else:
            m = re.match(r"(.+)\.s3\.amazonaws\.com", host)
            bucket = m.group(1) if m else parsed.path.strip("/").split("/")[0]
        path = parsed.path.strip("/")
    else:
        bucket, path = raw.split("/", 1)[0], (raw.split("/", 1) + [""])[1].strip("/")
    base = prefix or path
    session = boto3.session.Session(
        aws_access_key_id=env["EMPATICA_ACCESS_KEY_ID"],
        aws_secret_access_key=env["EMPATICA_SECRET_ACCESS_KEY"],
        region_name=region,
    )
    return session.client("s3"), bucket, base


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    env = load_env()
    s3, bucket, base = client_and_bucket(env)
    command = sys.argv[1]
    if command == "ls":
        prefix = sys.argv[2] if len(sys.argv) > 2 else base
        if prefix and not prefix.endswith("/"):
            prefix += "/"
        token = None
        shown = 0
        while shown < 200:
            args = dict(Bucket=bucket, Prefix=prefix, Delimiter="/", MaxKeys=200)
            if token:
                args["ContinuationToken"] = token
            page = s3.list_objects_v2(**args)
            for cp in page.get("CommonPrefixes", []):
                print("DIR ", cp["Prefix"])
                shown += 1
            for obj in page.get("Contents", []):
                print(f"FILE {obj['Key']}  {obj['Size']} bytes  {obj['LastModified']:%Y-%m-%d %H:%M}")
                shown += 1
            if not page.get("IsTruncated"):
                break
            token = page["NextContinuationToken"]
    elif command == "get":
        prefix, out = sys.argv[2], Path(sys.argv[3])
        out.mkdir(parents=True, exist_ok=True)
        paginator = s3.get_paginator("list_objects_v2")
        count = 0
        for page in paginator.paginate(Bucket=bucket, Prefix=prefix):
            for obj in page.get("Contents", []):
                if obj["Key"].endswith("/"):
                    continue
                target = out / obj["Key"].replace("/", "__")
                s3.download_file(bucket, obj["Key"], str(target))
                count += 1
        print(f"downloaded {count} files to {out}")
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
