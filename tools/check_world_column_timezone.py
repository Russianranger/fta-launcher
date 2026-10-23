#!/usr/bin/env python3
"""Reproduce the Thor metadata discrepancy using the actual compiled Kotlin queries.

Runs only in a fresh disposable host MariaDB sandbox. Requires the unchanged
private seed, production Kotlin classes, and Kotlin stdlib; exports hashes/flags,
never SQL, rows, credentials, or private paths. Does not execute Android code.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile

from create_world_updater_lock import (COLUMN_TIME_ZONE_SQL, ROOT, SCHEMAS, baseline_seed,
    identifier, inventory, postconditions, sandbox, schema_scoped_client, source_policy_probe)


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def java_list(items: list[str]) -> str:
    return 'List.of(' + ','.join(json.dumps(item) for item in items) + ')'


def java_schema(value: dict) -> str:
    fields = [json.dumps(value['name']), java_list(value['tableNames']), java_list(value['triggerNames'])]
    fields += [str(value[name]) for name in ('routineCount', 'eventCount', 'receiptCount', 'receiptBytes')]
    fields += [json.dumps(value['receiptSha256'])]
    fields += [str(value[name]) for name in ('releaseReceiptCount', 'releaseReceiptBytes')]
    fields += [json.dumps(value['releaseReceiptSha256'])]
    fields += [str(value[name]) for name in ('columnCount', 'columnBytes')]
    fields += [json.dumps(value['columnSha256'])]
    return 'new WorldUpdaterSchema(' + ','.join(fields) + ')'


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('server', 'initializer', 'client', 'database-zip', 'kotlin-classes', 'kotlin-stdlib', 'device-report', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    lock_bytes = (ROOT / 'manifests/world-updater-lock.json').read_bytes()
    lock = json.loads(lock_bytes)
    fixture = json.loads((ROOT / 'docs/evidence/database-2026-10-22-world-column-snapshots.json').read_text())
    device_bytes = args.device_report.read_bytes()
    device = json.loads(device_bytes)
    observation = device['worldColumnDiagnostic']
    assert device['appVersionCode'] == 9 and device['apkSha256'] == '1b478f1c1d99ca4928b47429068e6ad7adc6710540d9af4ecfbd0d3bbd6e09aa'
    assert observation['worldUpdaterLockSha256'] == sha(lock_bytes) == fixture['worldUpdaterLockSha256']
    for key in ('sourceTreeSha256', 'worldExecutableSha256'):
        assert observation[key] == fixture[key]
    assert observation['attemptId'] == device['lastDiagnostic']['attemptId']
    plan = inventory(args.database_zip)
    assert plan.archive_sha256 == lock['archiveSha256']
    world = next(schema for schema in lock['schemas'] if schema['name'] == 'acore_world')
    assert world['tableNames'][103] == 'game_event'
    includes = 'List.of(' + ','.join('new WorldIncludeDirectory(' + ','.join(json.dumps(item[key])
        for key in ('path', 'state', 'schema')) + ')' for item in lock['includeDirectories']) + ')'
    source = '''import java.nio.file.*; import java.util.*; import com.russianranger.fta.runtime.*;
public class TimezoneProbe {
 static WorldUpdaterSchema[] schemas = {SCHEMAS};
 static String json(Object o) {
  if(o instanceof String s)return "\\\""+s.replace("\\\\","\\\\\\\\").replace("\\\"","\\\\\\\"")+"\\\"";
  if(o instanceof Map<?,?> m){List<String> a=new ArrayList<>();for(var e:m.entrySet())a.add(json(e.getKey())+":"+json(e.getValue()));return "{"+String.join(",",a)+"}";}
  if(o instanceof Iterable<?> v){List<String>a=new ArrayList<>();for(var e:v)a.add(json(e));return "["+String.join(",",a)+"]";}
  return o.toString();
 }
 public static void main(String[] args)throws Exception {
  int i=Integer.parseInt(args[1]); var schema=schemas[i];
  if(args[0].equals("emit")) {System.out.print(WorldUpdaterEvidence.INSTANCE.schemaSql(schema,INCLUDES));return;}
  if(args[0].equals("snapshot")){System.out.print(WorldColumnEvidence.INSTANCE.schemaSql(schema));return;}
  String data=Files.readString(Path.of(args[2]));
  if(args[0].equals("strict")){System.out.print(json(WorldUpdaterEvidence.INSTANCE.parseValidation(data,schema)));return;}
  var names=new ArrayList<>(schema.getTableNames());names.remove("fta_release_updates");
  boolean after=args[0].equals("after");
  var before=WorldColumnEvidence.INSTANCE.parseSnapshot(after?Files.readString(Path.of(args[3])):data,schema,names);
  var snapshot=after?WorldColumnEvidence.INSTANCE.parseSnapshot(data,schema,schema.getTableNames()):null;
  var receipt=WorldColumnEvidence.INSTANCE.receiptMap("00000000-0000-0000-0000-000000000001",WORLD,LOCK,TREE,schema,names,before,snapshot);
  String text=json(receipt);
  if(WorldColumnEvidence.INSTANCE.safeReceipt(text,"00000000-0000-0000-0000-000000000001",WORLD,LOCK,TREE,schema,names)==null)throw new AssertionError("Receipt round trip failed");
  System.out.print(json(receipt.get(after?"after":"before")));
 }
}'''
    source = source.replace('SCHEMAS', ','.join(java_schema(s) for s in lock['schemas'])).replace('INCLUDES', includes)
    source = source.replace('WORLD', json.dumps(fixture['worldExecutableSha256'])).replace('LOCK', json.dumps(sha(lock_bytes))).replace('TREE', json.dumps(lock['sourceTreeSha256']))
    with tempfile.TemporaryDirectory(prefix='fta-tz-probe-') as temp:
        work = Path(temp)
        java = work / 'TimezoneProbe.java'
        java.write_text(source)
        classpath = ':'.join(str(p.resolve()) for p in (work, args.kotlin_classes, args.kotlin_stdlib))
        subprocess.run(['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-cp', classpath, str(java)],
                       check=True, capture_output=True)

        def kotlin(mode: str, index: int, data: str | None = None):
            cmd = ['java', '-cp', classpath, 'TimezoneProbe', mode, str(index)]
            if data is not None:
                (work / 'query.out').write_text(data)
                cmd.append(str(work / 'query.out'))
                if mode == 'before':
                    (work / 'before.out').write_text(data)
                if mode == 'after':
                    cmd.append(str(work / 'before.out'))
            value = subprocess.run(cmd, check=True, capture_output=True, text=True, timeout=30).stdout
            return json.loads(value) if data is not None else value

        queries = [kotlin('emit', index) for index in range(3)]
        snapshot_sql = kotlin('snapshot', 2)
        assert all(q.startswith(COLUMN_TIME_ZONE_SQL) for q in [*queries, snapshot_sql])
        legacy_snapshot = snapshot_sql.removeprefix(COLUMN_TIME_ZONE_SQL)
        proof = {'formatVersion': 1, 'scope': 'host-source-policy-model-only', 'compiledWorldExecuted': False,
            'androidExecuted': False, 'deviceReportSha256': sha(device_bytes), 'archiveSha256': plan.archive_sha256,
            'worldUpdaterLockSha256': sha(lock_bytes), 'sourceTreeSha256': lock['sourceTreeSha256'],
            'referenceSessionTimeZone': '+07:00', 'reproducedDeviceSessionTimeZone': '-06:00',
            'productionSqlSha256': [sha(q.encode()) for q in queries], 'snapshotSqlSha256': sha(snapshot_sql.encode()),
            'productionClassSha256': {name: sha((args.kotlin_classes / 'com/russianranger/fta/runtime' / (name + '.class')).read_bytes())
                for name in ('WorldUpdaterEvidence', 'WorldColumnEvidence')}, 'snapshots': {}}
        offsets = ['-06:00', '+00:00', '+05:30', '+07:00', '+13:00']
        with sandbox(str(args.server), str(args.initializer), str(args.client), transport='loopback') as cli:
            assert cli.query('SELECT VERSION();') == '12.3.2-MariaDB'
            global_zone = cli.query('SELECT @@global.time_zone;')
            for schema in SCHEMAS:
                cli.run(sql=f'CREATE DATABASE {identifier(schema)} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;', capture=False)
            with zipfile.ZipFile(args.database_zip) as archive:
                for entry in plan.seed():
                    cli.import_member(archive, entry)
                baseline_seed(cli, plan)
                scoped = schema_scoped_client(cli)
                for phase in ('before', 'after'):
                    if phase == 'after':
                        proof['transitions'] = source_policy_probe(scoped, archive, plan)
                    for zone, expected in [('+07:00', fixture[phase]), ('-06:00', observation[phase])]:
                        actual = kotlin(phase, 2, scoped.query(f"SET SESSION time_zone='{zone}';\n" + legacy_snapshot) + '\n')
                        assert actual == expected, f'{phase} legacy snapshot did not reproduce {zone}'
                    for zone in offsets:
                        actual = kotlin(phase, 2, scoped.query(f"SET SESSION time_zone='{zone}';\n" + snapshot_sql) + '\n')
                        assert actual == fixture[phase], f'{phase} corrected snapshot depends on inherited time zone'
                    proof['snapshots'][phase] = {'legacyDeviceReproduced': True, 'legacyHostReproduced': True,
                        'correctedAllOffsetsMatchReference': offsets, 'aggregate': fixture[phase]['aggregate']}
            assert postconditions(scoped) == lock['schemas']
            for zone in offsets:
                for index, query in enumerate(queries):
                    flags = kotlin('strict', index, scoped.query(f"SET SESSION time_zone='{zone}';\n" + query) + '\n')
                    assert len(flags) == 8 and all(flags.values())
            # Compare only catalog metadata; private game_event rows are never queried/exported.
            metadata = "SELECT column_name,HEX(column_type),HEX(is_nullable),IF(column_default IS NULL,'~',HEX(column_default)),IF(collation_name IS NULL,'~',HEX(collation_name)),HEX(extra) FROM information_schema.columns WHERE table_schema='acore_world' AND table_name='game_event' ORDER BY ordinal_position;"
            versions = [scoped.query(f"SET SESSION time_zone='{zone}';\n" + metadata).splitlines() for zone in ('+07:00', '-06:00')]
            differences = {}
            for left, right in zip(*versions, strict=True):
                a, b = left.split('\t'), right.split('\t')
                assert a[0] == b[0]
                if a != b:
                    differences[a[0]] = [name for name, x, y in zip(('type','nullable','default','collation','extra'),a[1:],b[1:],strict=True) if x != y]
            assert differences == {'start_time': ['default'], 'end_time': ['default']}
            assert cli.query('SELECT @@global.time_zone;') == global_zone
            # Fault injection only into this disposable test instance, after all original checks.
            scoped.run(sql='ALTER TABLE acore_world.game_event ALTER COLUMN announce SET DEFAULT 1;', capture=False)
            flags = kotlin('strict', 2, scoped.query(queries[2]) + '\n')
            assert flags['columnInventoryMatches'] is False and all(v for k,v in flags.items() if k != 'columnInventoryMatches')
            proof.update(differingComponents=differences, originalSqlUnchanged=True, strictExpectedMetadataUnchanged=True,
                fullSeedBaselinePassed=True, all24PostconditionsPassedAcrossOffsets=offsets,
                schemaPrivilegesOnly=True, globalTimeZoneUnchanged=True, realSchemaMutationRejected=True,
                productionKotlinParserAndReceiptRoundTripPassed=True)
        proof['disposableSandboxShutdownCompleted'] = True
        args.output.write_text(json.dumps(proof, indent=2, sort_keys=True) + '\n')
        print(json.dumps({'status':'host-timezone-regression-passed','originalChecks':24,'testedOffsets':len(offsets)}))


if __name__ == '__main__':
    main()
