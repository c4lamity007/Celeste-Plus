"""Static Java runtime-package access checks across independent module classloaders."""
from pathlib import Path
import json,struct,sys,collections

def info(raw):
 pos=8
 def take(n):
  nonlocal pos
  out=raw[pos:pos+n];pos+=n
  if len(out)!=n:raise ValueError('truncated class')
  return out
 def u2():return struct.unpack('>H',take(2))[0]
 cp={}; i=1; count=u2()
 while i<count:
  tag=take(1)[0]
  if tag==1:cp[i]=(tag,take(u2()).decode('utf-8',errors='replace'))
  elif tag in (7,8,16,19,20):cp[i]=(tag,u2())
  elif tag in (9,10,11,12,17,18):cp[i]=(tag,u2(),u2())
  elif tag in (3,4):take(4)
  elif tag in (5,6):take(8);i+=1
  elif tag==15:take(3)
  else:raise ValueError(tag)
  i+=1
 def utf(i):return cp[i][1]
 def cls(i):return utf(cp[i][1]) if i else None
 flags=u2();name=cls(u2());parent=cls(u2());interfaces=[cls(u2()) for _ in range(u2())]
 def attributes():
  for _ in range(u2()):u2();take(struct.unpack('>I',take(4))[0])
 def members():
  out={}
  for _ in range(u2()):
   access=u2();key=(utf(u2()),utf(u2()));attributes();out[key]=access
  return out
 fields=members();methods=members();refs=[];class_refs=set()
 for v in cp.values():
  if v[0]==7:
   reference=utf(v[1])
   if reference.startswith('['):
    reference=reference.lstrip('[')
    reference=reference[1:-1] if reference.startswith('L') and reference.endswith(';') else ''
   if reference:class_refs.add(reference)
  if v[0] in (9,10,11):
   nt=cp[v[2]];refs.append((v[0],cls(v[1]),utf(nt[1]),utf(nt[2])))
 return {'name':name,'flags':flags,'parent':parent,'interfaces':interfaces,'fields':fields,'methods':methods,'refs':refs,'class_refs':class_refs}

def audit(classes,closures):
 owner=collections.defaultdict(set)
 for mid,names in closures.items():
  for n in names:owner[n].add(mid)
 def resolve(target,kind,key,seen=None):
  seen=set() if seen is None else seen
  if target in seen or target not in classes:return None
  seen.add(target);c=classes[target]
  if key in c[kind]:return target,c[kind][key]
  # Constructors are not inherited. Field resolution searches interfaces before the superclass.
  if kind=='methods' and key[0]=='<init>':return None
  bases=[*c['interfaces'],c['parent']] if kind=='fields' else [c['parent'],*c['interfaces']]
  for base in bases:
   result=resolve(base,kind,key,seen)
   if result:return result
 def subclass(source,target):
  seen=set()
  while source in classes and source not in seen:
   if source==target:return True
   seen.add(source);source=classes[source]['parent']
  return False
 issues=[];checked=0
 for mid,names in closures.items():
  for source in names:
   c=classes[source]
   for target in sorted(c.get('class_refs',())):
    if target in classes and target not in owner and not classes[target]['flags']&1:
     issues.append([mid,source,target,'<class>','','nonpublic resident class reference'])
   for tag,target,name,descriptor in c['refs']:
    if target not in classes:continue
    member=resolve(target,'fields' if tag==9 else 'methods',(name,descriptor))
    if target not in owner and not classes[target]['flags']&1:
     issues.append([mid,source,target,name,descriptor,'nonpublic resident class']);continue
    if not member:
     if name=='<init>' and target not in owner:
      issues.append([mid,source,target,name,descriptor,'unresolved resident constructor'])
     continue
    declared,flags=member
    if declared in owner:continue
    checked+=1
    if flags&1:continue
    if flags&4 and subclass(source,declared):
     # CP references alone cannot prove the protected instance receiver is this
     # subclass (nor distinguish a legal invokespecial super invocation).
     # Fail closed until an explicit bytecode receiver proof is implemented.
     if flags&8:continue
     issues.append([mid,source,declared,name,descriptor,'protected resident instance access requires receiver evidence']);continue
    issues.append([mid,source,declared,name,descriptor,'nonpublic resident member'])
 return {'checked':checked,'issues':issues}

