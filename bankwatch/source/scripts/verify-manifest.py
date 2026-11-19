#!/usr/bin/env python3
"""Verify the public source inventory. Private execution logs are intentionally not included."""
import argparse,hashlib,json,re,sys
from pathlib import Path,PurePosixPath
MANIFEST_PATH='docs/verification/BankWatch_verification_manifest.json'
def require(condition,message):
    if not condition:raise ValueError(message)
def parse_json(text):
    return json.loads(text)
def digest(data):
    return hashlib.sha256(data).hexdigest()
def validate_archive(manifest):
    require(manifest['kind']=='bankwatch.public_source_snapshot' and manifest['schema_version']=='public-1.0.0','Use the public-edition manifest')
    files={}
    for row in manifest['inventory']:
        name=row['path'];path=PurePosixPath(name)
        require(not path.is_absolute() and '..' not in path.parts and name not in files,'Invalid or duplicate source path')
        require(re.fullmatch('[0-9a-f]{64}',row['sha256']) and row['bytes']>=0,'Invalid fingerprint')
        files[name]=row
    require(MANIFEST_PATH not in files and len(files)==52,'Inventory/self-exclusion mismatch')
    require(manifest['embedded_blobs']=={} and manifest['evidence_runs']==[],'Private raw evidence must not be included')
    require(manifest['summary']['raw_execution_evidence_included'] is False,'Public summary scope mismatch')
    runtime=manifest['runtime_source_sha256']
    require(len(runtime)==37,'Runtime scope mismatch')
    for name,value in runtime.items():require(files[name]['sha256']==value,'Runtime differs from verified v1.10: '+name)
    return files
def validate_project(root,files):
    for name,row in files.items():
        path=root/name
        require(not path.is_symlink() and path.resolve().is_relative_to(root) and path.is_file(),'Missing or unsafe source: '+name)
        raw=path.read_bytes()
        require(len(raw)==row['bytes'] and digest(raw)==row['sha256'],'Changed source: '+name)
    return len(files)
def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[1])
    parser.add_argument('--manifest',type=Path)
    parser.add_argument('--archive-only',action='store_true')
    parser.add_argument('--json',action='store_true')
    args=parser.parse_args(argv)
    try:
        root=args.root.resolve();path=args.manifest or root/MANIFEST_PATH
        manifest=parse_json(path.read_text(encoding='utf-8'));files=validate_archive(manifest)
        count=0 if args.archive_only else validate_project(root,files)
        result=dict(status='PASS',schema_version=manifest['schema_version'],project_files_checked=count,
          runtime_files_matching_private_v110=37,scope='Public source fingerprints and published summary only',
          raw_execution_evidence_checked=False,business_tests_executed=False)
    except (OSError,ValueError,KeyError,TypeError) as error:
        result=dict(status='FAIL',error=str(error));print(json.dumps(result,ensure_ascii=True));return 1
    print(json.dumps(result,ensure_ascii=True) if args.json else 'PASS: public source52 / runtime37; no private raw evidence or business tests executed')
    return 0
if __name__=='__main__':sys.exit(main())
