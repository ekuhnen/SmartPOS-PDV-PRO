#!/usr/bin/env python3
"""PILOT-STAB-02A reproducible, best-effort static localization audit."""
from __future__ import annotations

import hashlib, json, re, subprocess, sys
import xml.etree.ElementTree as ET
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

GENERATOR, VERSION = "scripts/localization_audit.py", "1.1"
ROOT = Path(__file__).resolve().parent.parent
APP, RES = ROOT / "android/app", ROOT / "android/app/src/main/res"
SOURCE = ROOT / "android/app/src/main/java"
OUT_JSON, OUT_MD = ROOT / "localization_audit.json", ROOT / "LOCALIZATION_AUDIT.md"
LOCALES = {x: RES / y for x, y in {"pt":"values/strings.xml", "es":"values-es/strings.xml", "en":"values-en/strings.xml", "gn":"values-gn/strings.xml"}.items()}
PRINT_TARGETS = {
 "PrinterHelper": SOURCE/"com/plugpdv/pdv/utils/PrinterHelper.kt",
 "PrinterUtil8": SOURCE/"com/plugpdv/pdv/hardware/printer/PrinterUtil8.java",
 "GeneralPrinterUtil": SOURCE/"com/plugpdv/pdv/hardware/printer/GeneralPrinterUtil.java",
 "SunmiPrinter": SOURCE/"com/plugpdv/pdv/hardware/SunmiPrinter.kt",
 "GertecPrinter": SOURCE/"com/plugpdv/pdv/hardware/GertecPrinter.kt",
 "DejavooPrinter": SOURCE/"com/plugpdv/pdv/hardware/DejavooPrinter.kt",
 "DspreadPrinter": SOURCE/"com/plugpdv/pdv/hardware/DspreadPrinter.kt",
 "KozenPrinter": SOURCE/"com/plugpdv/pdv/hardware/KozenPrinter.kt",
 "ReceiptData": SOURCE/"com/plugpdv/pdv/hardware/printer/ReceiptData.java",
}
ANDROID = "{http://schemas.android.com/apk/res/android}"
XML_ATTRS = {ANDROID+"text":"android:text", ANDROID+"hint":"android:hint", ANDROID+"title":"android:title", ANDROID+"contentDescription":"android:contentDescription", "{http://schemas.android.com/apk/res-auto}title":"app:title", "{http://schemas.android.com/apk/res-auto}subtitle":"app:subtitle"}
PH_RE = re.compile(r"%(?!%)(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[sdf]")
STR_RE = re.compile(r'"((?:\\.|[^"\\])*)"')
CUR_RE = re.compile(r"R\$|Gs\.|₲|(?<![A-Za-z0-9_])\$(?![A-Za-z0-9_{])")
SEVS = ("P0-L10N", "P1-L10N", "P2-L10N", "P3-L10N")
SHARED = {"plugpdv","pix","id","ids","api","qr","wifi","bluetooth","usb","brl","pyg","ars","usd","eur","cpf","cnpj","email","login"}
CONSTANTS = {"AUTH_REQUIRED","RECONCILIATION_REQUIRED","OPEN_TABLE","ADD_ITEM","CREDIT","DEBIT","CASH","PIX","MONEY","CARD","PENDING","APPROVED","REJECTED","CANCELLED","COMPLETED","SYNCED","PAUSED","BRL","PYG","ARS","USD","EUR","AVAILABLE","OCCUPIED","RESERVED"}

def rel(p): return p.relative_to(ROOT).as_posix()
def git(*a):
 r=subprocess.run(["git",*a],cwd=ROOT,text=True,encoding="utf-8",errors="replace",stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
 return r.stdout.strip() if r.returncode==0 else "UNKNOWN"
def ph(s): return PH_RE.findall(s.replace("%%",""))
def parse_strings(p):
 out={}
 for e in ET.parse(p).getroot().findall("string"):
  if not e.attrib.get("name"): continue
  text="".join(e.itertext())
  out[e.attrib["name"]]={"value":text,"translatable":e.attrib.get("translatable","true").lower()!="false","placeholders":ph(text)}
 return out
def suspicious(pt,es):
 a=re.sub(r"\s+"," ",pt.strip()).casefold(); b=re.sub(r"\s+"," ",es.strip()).casefold()
 words=re.findall(r"[A-Za-zÀ-ÿ]+",a)
 return a==b and bool(words) and not all(w in SHARED for w in words) and bool(re.search(r"[ãõçêô]|\b(não|você|usuário|impressão|conexão|atenção)\b",a))
def resources():
 maps={k:parse_strings(v) for k,v in LOCALES.items()}; base={k for k,v in maps["pt"].items() if v["translatable"]}
 cov={}; missing={}; extra={}; mismatches=[]; same=[]
 for loc in LOCALES:
  keys={k for k,v in maps[loc].items() if v["translatable"]}; present=base&keys
  missing[loc]=sorted(base-keys); extra[loc]=sorted(keys-base)
  cov[loc]={"total_base_keys":len(base),"translated_keys":len(present),"missing_keys":len(base-keys),"extra_keys":len(keys-base),"coverage_pct":round(len(present)/len(base)*100 if base else 100,2)}
  if loc!="pt":
   for key in sorted(present):
    if Counter(maps["pt"][key]["placeholders"])!=Counter(maps[loc][key]["placeholders"]):
     mismatches.append({"key":key,"locale":loc,"pt_placeholders":maps["pt"][key]["placeholders"],"translated_placeholders":maps[loc][key]["placeholders"],"pt_value":maps["pt"][key]["value"],"translated_value":maps[loc][key]["value"]})
    if loc=="es" and suspicious(maps["pt"][key]["value"],maps[loc][key]["value"]): same.append({"key":key,"value":maps[loc][key]["value"]})
 return {"coverage":cov,"missing_keys":missing,"extra_keys":extra,"placeholder_mismatches":mismatches,"suspicious_identical_es_pt":same,"non_translatable_base_keys":sorted(k for k,v in maps["pt"].items() if not v["translatable"])}

def visible(v):
 x=v.replace("\\n"," ").replace("\\t"," ").strip()
 return bool(x and x not in CONSTANTS and x.casefold() not in SHARED and not x.startswith(("http://","https://","@","?","content://")) and not re.fullmatch(r"[\d\s.,:/()\-+_=*#%|\\]+",x) and not re.fullmatch(r"[A-Z][A-Z0-9_.-]+",x) and re.search(r"[A-Za-zÀ-ÿ]",x))
def fixed_language(v):
 # Interpolated variables and resource expressions are data, not hardcoded prose.
 # Keep any literal prefix/suffix (for example "Erro: ${e.message}").
 return visible(re.sub(r"\$\{[^}]*\}|\$[A-Za-z_]\w*", "", v))
def fid(path,line,value): return "L10N-"+hashlib.sha1(f"{path}:{line}:{value}".encode()).hexdigest()[:12].upper()
def add(store,path,line,value,sev,cat,**meta):
 i=fid(path,line,value)
 if i not in store: store[i]={"id":i,"path":path,"line":line,"value":value,"severity":sev,"categories":[cat],"classification":"CONFIRMED",**meta}
 elif cat not in store[i]["categories"]: store[i]["categories"].append(cat)
 return store[i]
def review(store,path,line,value,cat,reason):
 i=fid(path,line,value); store[i]={"id":i,"path":path,"line":line,"value":value,"categories":[cat],"classification":"REVIEW_REQUIRED","reason":reason}
def category(store,name): return sorted((x for x in store.values() if name in x["categories"]),key=lambda x:(x["path"],str(x["line"]),x["id"]))

def xml_scan(store):
 files=[]; dirs={"layout","menu","navigation"}; essential=("checkout","payment","cashier","comanda","table","command","sale")
 for d in RES.iterdir():
  if d.is_dir() and d.name.split("-")[0] in dirs: files.extend(d.glob("*.xml"))
 for p in sorted(set(files)):
  try: root=ET.parse(p).getroot()
  except ET.ParseError: continue
  lines=p.read_text(encoding="utf-8").splitlines()
  for e in root.iter():
   for raw,v in e.attrib.items():
    if raw not in XML_ATTRS or not visible(v): continue
    line=next((n for n,s in enumerate(lines,1) if v in s),1)
    sev="P0-L10N" if CUR_RE.search(v) else ("P1-L10N" if any(x in p.name.casefold() for x in essential) else "P2-L10N")
    item=add(store,rel(p),line,v,sev,"hardcoded_xml",attribute=XML_ATTRS[raw],sink=XML_ATTRS[raw])
    if CUR_RE.search(v) and "currency" not in item["categories"]: item["categories"].append("currency")
 return [rel(p) for p in sorted(set(files))]

def ignored_line(s):
 x=s.strip()
 return not x or x.startswith(("//","/*","*","#")) or bool(re.search(r"\b(?:Log|Timber)\.(?:v|d|i|w|e|wtf)\s*\(",s)) or bool(re.search(r"\b(?:TAG|tag)\s*=",s))
def literals(s): return STR_RE.findall(s)
def ui_sink(s):
 for p,n in [(r"Toast\.makeText\s*\(","Toast"),(r"Snackbar\.make\s*\(","Snackbar"),(r"\.(?:setTitle|setMessage|setAction|setPositiveButton|setNegativeButton|setNeutralButton)\s*\(","Dialog"),(r"\.setText\s*\(","setText"),(r"\.text\s*=", "text")]:
  if re.search(p,s): return n
def print_sink(s):
 if re.search(r"\b(?:printText|printTextWithFont)\s*\(",s): return "USER_PRINTED_TEXT"
 if re.search(r"\b(?:showToast|Toast\.makeText|Snackbar\.make)\s*\(",s): return "USER_VISIBLE_PRINTER_ERROR"

def code_scan(store,reviews):
 files=sorted([*SOURCE.rglob("*.kt"),*SOURCE.rglob("*.java")]); print_paths={p.resolve() for p in PRINT_TARGETS.values()}; locale=[]
 for p in files:
  path=rel(p); isvm="ViewModel" in p.name; isp=p.resolve() in print_paths; text=p.read_text(encoding="utf-8",errors="replace")
  for no,line in enumerate(text.splitlines(),1):
   if ignored_line(line): continue
   vals=[v for v in literals(line) if fixed_language(v)]; sink=ui_sink(line); psink=print_sink(line) if isp else None
   for v in vals:
    sev="P0-L10N" if CUR_RE.search(v) else "P1-L10N"
    if psink:
     if psink=="USER_VISIBLE_PRINTER_ERROR" and re.search(r"Printer class:|Calling printer\.print|SDK Init Síncrono",v,re.I):
      review(reviews,path,no,v,"printing","Technical/debug toast is operator-visible, but localization intent requires manual confirmation")
      continue
     item=add(store,path,no,v,sev,"printing",sink=psink,printing_class=psink)
     if "hardcoded_code" not in item["categories"]: item["categories"].append("hardcoded_code")
    elif sink: item=add(store,path,no,v,sev,"hardcoded_code",sink=sink)
    elif isvm and re.search(r"(?:error|message|reason|status|blockReason).*\.(?:value|postValue)\s*=",line,re.I):
     item=add(store,path,no,v,"P1-L10N","viewmodel",sink="ViewModel state")
     if "hardcoded_code" not in item["categories"]: item["categories"].append("hardcoded_code")
    else: continue
    if CUR_RE.search(v) and "currency" not in item["categories"]: item["categories"].append("currency")
   if isvm and vals and re.search(r"(?:error|message|reason|status|blockReason).*\.(?:value|postValue)\s*=",line,re.I):
    for v in vals:
     item=add(store,path,no,v,"P1-L10N","viewmodel",sink="ViewModel state")
     if "hardcoded_code" not in item["categories"]: item["categories"].append("hardcoded_code")
  m=re.search(r"\bclass\s+(\w+)[^{:\n]*:\s*([\w.<>]+)",text)
  if m:
   cls,parent=m.groups(); localized="LanguageManager" in text or "getLocalizedContext" in text
   if "Activity" in parent and parent!="BaseActivity" and not localized:
    reason="Activity does not inherit BaseActivity; inheritance alone does not prove missing localization context"
    locale.append({"path":path,"class":cls,"parent":parent,"classification":"REVIEW_REQUIRED","reason":reason}); review(reviews,path,"class",cls,"locale_propagation",reason)
   elif any(x in cls or x in parent for x in ("Service","Worker","BottomSheet","Dialog")) and not localized and ("getString(" in text or "Notification" in text):
    reason="Presentation-capable context uses resources without demonstrable localized context"
    locale.append({"path":path,"class":cls,"parent":parent,"classification":"REVIEW_REQUIRED","reason":reason}); review(reviews,path,"class",cls,"locale_propagation",reason)
 return [rel(p) for p in files],locale

def printing_summary(store,reviews):
 out={}
 for name,p in PRINT_TARGETS.items():
  path=rel(p); text=p.read_text(encoding="utf-8",errors="replace") if p.exists() else ""; found=[x for x in category(store,"printing") if x["path"]==path]; rev=[x for x in reviews.values() if x["path"]==path and "printing" in x["categories"]]
  out[name]={"path":path,"scanned":p.exists(),"confirmed_user_facing_hardcoded_strings":len(found),"review_required_findings":len(rev),"localized_resource_usage":bool(re.search(r"R\.string\.|getString\s*\(",text)),"receipt_content":sum(x.get("printing_class")=="USER_PRINTED_TEXT" for x in found),"operator_error_messages":sum(x.get("printing_class")=="USER_VISIBLE_PRINTER_ERROR" for x in found),"logs_debug_excluded":len(re.findall(r"\b(?:Log|Timber)\.(?:v|d|i|w|e|wtf)\s*\(",text))}
 return out

def build_report():
 required=[*LOCALES.values(),SOURCE,RES,*PRINT_TARGETS.values()]; res=resources(); store={}; reviews={}; scanned_xml=xml_scan(store); scanned_code,locale=code_scan(store,reviews)
 for loc,keys in res["missing_keys"].items():
  if loc!="pt":
   for key in keys: add(store,rel(LOCALES[loc]),"resource",key,"P3-L10N","missing_translation",sink=loc)
 for x in res["placeholder_mismatches"]: add(store,rel(LOCALES[x["locale"]]),"resource",x["key"],"P1-L10N","placeholder_mismatch",sink=x["locale"])
 for x in res["suspicious_identical_es_pt"]: review(reviews,rel(LOCALES["es"]),"resource",x["key"],"suspicious_identical_es_pt","Spanish value appears to retain Portuguese-specific language")
 findings=sorted(store.values(),key=lambda x:(x["path"],str(x["line"]),x["id"])); sev=Counter(x["severity"] for x in findings); code=category(store,"hardcoded_code"); kt=sum(x["path"].endswith(".kt") for x in code); java=sum(x["path"].endswith(".java") for x in code)
 summary={"hardcoded_xml_count":len(category(store,"hardcoded_xml")),"hardcoded_kotlin_count":kt,"hardcoded_java_count":java,"hardcoded_code_total":kt+java,"viewmodel_violations_count":len(category(store,"viewmodel")),"printing_violations_count":len(category(store,"printing")),"currency_violations_count":len(category(store,"currency")),"locale_propagation_confirmed_count":len(category(store,"locale_propagation")),"review_required_count":len(reviews),"canonical_confirmed_findings_count":len(findings),"severity_counts":{s:sev[s] for s in SEVS},"severity_classified_total":sum(sev.values())}
 return {"generator":GENERATOR,"generator_version":VERSION,"generated_at":datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00","Z"),"git_commit":git("rev-parse","--short=12","HEAD"),"git_branch":git("branch","--show-current"),"audit_semantics":"PASS means the static audit completed and is internally consistent; defect severities feed STAB-02B/02C.","limitations":["Best-effort static discovery; physical/runtime walkthrough remains required.","Single-line sink heuristics can miss literals assembled across multiple lines or indirect presentation paths.","REVIEW_REQUIRED findings are excluded from confirmed counts and severity totals.","All configured scan targets were processed when missing_scan_targets is empty."],"scan":{"configured_locales":{k:rel(v) for k,v in LOCALES.items()},"xml_files_scanned":scanned_xml,"source_files_scanned":scanned_code,"kotlin_files_scanned":sum(x.endswith(".kt") for x in scanned_code),"java_files_scanned":sum(x.endswith(".java") for x in scanned_code),"missing_scan_targets":[rel(p) for p in required if not p.exists()]},"resource_coverage":res["coverage"],"missing_keys":res["missing_keys"],"extra_keys":res["extra_keys"],"placeholder_mismatches":res["placeholder_mismatches"],"suspicious_identical_es_pt":res["suspicious_identical_es_pt"],"non_translatable_base_keys":res["non_translatable_base_keys"],"summary":summary,"findings":findings,"hardcoded_xml":category(store,"hardcoded_xml"),"hardcoded_code":code,"viewmodel_violations":category(store,"viewmodel"),"printing_violations":category(store,"printing"),"currency_violations":category(store,"currency"),"locale_propagation":{"findings":locale},"review_required":sorted(reviews.values(),key=lambda x:(x["path"],str(x["line"]))),"printing_summary":printing_summary(store,reviews)}

def markdown(r):
 s=r["summary"]; sev=s["severity_counts"]; miss=r["missing_keys"]["es"]
 out=["# PILOT-STAB-02A — Localization Coverage Audit Baseline","",f"**Generated At:** {r['generated_at']}  ",f"**Git Branch:** `{r['git_branch']}`  ",f"**Git Commit:** `{r['git_commit']}`  ",f"**Generator Version:** `{r['generator_version']}`","","## Audit Scope and Verdict","","This is a reproducible static localization baseline. It is best-effort static discovery; a physical/runtime walkthrough remains required.","","All configured scan targets were processed successfully." if not r["scan"]["missing_scan_targets"] else "One or more configured scan targets were missing.","","`PILOT-STAB-02A: PASS`","","PASS describes audit execution and internal consistency. P0/P1 findings remain remediation work for STAB-02B/02C.","","## Resource Coverage","","| Locale | Total base keys | Translated keys | Missing keys | Extra keys | Coverage |","|---|---:|---:|---:|---:|---:|"]
 for loc in LOCALES:
  x=r["resource_coverage"][loc]; out.append(f"| {loc.upper()} | {x['total_base_keys']} | {x['translated_keys']} | {x['missing_keys']} | {x['extra_keys']} | {x['coverage_pct']:.2f}% |")
 out += ["","## Missing Spanish Keys","",f"**Count:** {len(miss)}","","```text",*miss,"```","","## Suspicious Spanish Values Identical to Portuguese","",f"**Count:** {len(r['suspicious_identical_es_pt'])}",""]
 out += [f"- `{x['key']}`: {x['value']}" for x in r["suspicious_identical_es_pt"]] or ["None detected by the conservative heuristic."]
 out += ["","## Placeholder Validation","",f"**Mismatches:** {len(r['placeholder_mismatches'])}","","Supported forms include `%s`, `%d`, `%f`, `%1$s`, and `%2$d`.","","## Confirmed Finding Summary","",f"- Hardcoded XML: {s['hardcoded_xml_count']}",f"- Hardcoded Kotlin: {s['hardcoded_kotlin_count']}",f"- Hardcoded Java: {s['hardcoded_java_count']}",f"- Hardcoded code total: {s['hardcoded_code_total']}",f"- ViewModel: {s['viewmodel_violations_count']}",f"- Printing: {s['printing_violations_count']}",f"- Currency: {s['currency_violations_count']}",f"- Locale propagation confirmed: {s['locale_propagation_confirmed_count']}",f"- Review required: {s['review_required_count']}","","## Severity","","| Severity | Count |","|---|---:|",*[f"| {x} | {sev[x]} |" for x in SEVS],f"| Total unique confirmed findings | {s['severity_classified_total']} |","","Severity totals are calculated once from canonical finding IDs. Category arrays may reference the same ID without increasing the severity total.","","## Printing Breakdown","","| Target | Scanned | Confirmed | Review required | Resource usage | Receipt | Operator error | Logs/debug excluded |","|---|---|---:|---:|---|---:|---:|---:|"]
 for name,x in r["printing_summary"].items(): out.append(f"| {name} | {'yes' if x['scanned'] else 'no'} | {x['confirmed_user_facing_hardcoded_strings']} | {x['review_required_findings']} | {'yes' if x['localized_resource_usage'] else 'no'} | {x['receipt_content']} | {x['operator_error_messages']} | {x['logs_debug_excluded']} |")
 out += ["","## Review Required","","These entries are excluded from confirmed counts and severity arithmetic.",""]
 out += [f"- `{x['id']}` `{x['path']}` — {x['value']}: {x['reason']}" for x in r["review_required"]]
 out += ["","## Known Limitations","",*[f"- {x}" for x in r["limitations"]],"","## Detailed Findings","","The complete canonical findings and category views are available in `localization_audit.json`.","","## Baseline Verdict","","`PILOT-STAB-02A: PASS`",""]
 return "\n".join(out)

def validate(r,md):
 e=[]; s=r["summary"]
 if r["scan"]["missing_scan_targets"]: e.append("missing configured targets: "+", ".join(r["scan"]["missing_scan_targets"]))
 for arr,key in [("hardcoded_xml","hardcoded_xml_count"),("hardcoded_code","hardcoded_code_total"),("viewmodel_violations","viewmodel_violations_count"),("printing_violations","printing_violations_count"),("currency_violations","currency_violations_count")]:
  if len(r[arr])!=s[key]: e.append(arr+" summary mismatch")
 if s["hardcoded_kotlin_count"]+s["hardcoded_java_count"]!=s["hardcoded_code_total"]: e.append("language count mismatch")
 if sum(s["severity_counts"].values())!=len(r["findings"]) or len(r["findings"])!=s["severity_classified_total"]: e.append("severity arithmetic mismatch")
 if len({x["id"] for x in r["findings"]})!=len(r["findings"]): e.append("duplicate finding IDs")
 for loc,x in r["resource_coverage"].items():
  if x["translated_keys"]+x["missing_keys"]!=x["total_base_keys"]: e.append(loc+" coverage arithmetic mismatch")
  expected=round(x["translated_keys"]/x["total_base_keys"]*100 if x["total_base_keys"] else 100,2)
  if expected!=x["coverage_pct"]: e.append(loc+" coverage percentage mismatch")
 tokens=[f"Hardcoded XML: {s['hardcoded_xml_count']}",f"Hardcoded Kotlin: {s['hardcoded_kotlin_count']}",f"Hardcoded Java: {s['hardcoded_java_count']}",f"Review required: {s['review_required_count']}",*[f"| {x} | {s['severity_counts'][x]} |" for x in SEVS]]
 if any(x not in md for x in tokens): e.append("Markdown/JSON summary mismatch")
 json.loads(json.dumps(r,ensure_ascii=False)); return e

def main():
 if sys.platform=="win32": sys.stdout.reconfigure(encoding="utf-8")
 r=build_report(); md=markdown(r); errors=validate(r,md)
 if errors:
  print("Audit consistency validation FAILED:",*errors,sep="\n- ",file=sys.stderr); return 1
 OUT_JSON.write_text(json.dumps(r,ensure_ascii=False,indent=2)+"\n",encoding="utf-8"); OUT_MD.write_text(md,encoding="utf-8")
 persisted=json.loads(OUT_JSON.read_text(encoding="utf-8"))
 if persisted["summary"]!=r["summary"]: print("Persisted JSON validation failed",file=sys.stderr); return 1
 print(f"PILOT-STAB-02A audit PASS — generator {VERSION}"); print(json.dumps(r["summary"],sort_keys=True)); print(f"Reports: {OUT_MD.name}, {OUT_JSON.name}"); return 0
if __name__=="__main__": raise SystemExit(main())
