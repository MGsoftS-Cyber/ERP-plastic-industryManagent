#!/usr/bin/env python3
"""Create a local demo .env with random passwords; never overwrite an existing file."""
from pathlib import Path
import os,re,secrets
root=Path(__file__).resolve().parent.parent
target=root/'.env'
if target.exists():raise SystemExit('.env already exists; retain it and add missing settings from .env.example.')
text=(root/'.env.example').read_text()
text=re.sub(r'^([A-Z0-9_]+_PASSWORD)=.*$',lambda m:m[1]+'='+secrets.token_urlsafe(32),text,flags=re.M)
with os.fdopen(os.open(target,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600),'w') as output:output.write(text)
print('Created local demo settings. Password values were not printed.')
