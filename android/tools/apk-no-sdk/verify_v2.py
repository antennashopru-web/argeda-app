import sys,struct,hashlib
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
d=open(sys.argv[1],'rb').read()
eocd=d.rfind(b'PK\x05\x06'); cdsize,cdoff=struct.unpack('<II',d[eocd+12:eocd+20])
assert d[cdoff-16:cdoff]==b'APK Sig Block 42'
size=struct.unpack('<Q',d[cdoff-24:cdoff-16])[0]; start=cdoff-size-8
assert struct.unpack('<Q',d[start:start+8])[0]==size, 'block size mismatch'
p=start+8; blocks={}
while p<cdoff-24:
    l=struct.unpack('<Q',d[p:p+8])[0]; i=struct.unpack('<I',d[p+8:p+12])[0]; blocks[i]=d[p+12:p+8+l]; p+=8+l
assert p==cdoff-24, 'pairs do not end at size field'
v2=blocks[0x7109871a]
def lp(b,o): l=struct.unpack('<I',b[o:o+4])[0]; return b[o+4:o+4+l],o+4+l
signers,e=lp(v2,0); assert e==len(v2); signer,e=lp(signers,0); assert e==len(signers)
signed,o=lp(signer,0); sigs,o=lp(signer,o); pub,o=lp(signer,o); assert o==len(signer)
digests,o2=lp(signed,0); certs,o2=lp(signed,o2); attrs,o2=lp(signed,o2); assert o2==len(signed) and attrs==b''
dg,e=lp(digests,0); assert e==len(digests); algo=struct.unpack('<I',dg[:4])[0]; digest,e=lp(dg,4); assert e==len(dg)
cert,e=lp(certs,0); assert e==len(certs); sg,e=lp(sigs,0); assert e==len(sigs); salgo=struct.unpack('<I',sg[:4])[0]; sig,e=lp(sg,4); assert e==len(sg)
assert algo==salgo==0x0103
c=x509.load_der_x509_certificate(cert)
c.public_key().verify(sig,signed,padding.PKCS1v15(),hashes.SHA256()); print('signature over signed-data: OK')
assert pub==c.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo), 'public key mismatch'
contents=d[:start]; cd=d[cdoff:cdoff+cdsize]; e=bytearray(d[eocd:]); e[16:20]=struct.pack('<I',start)
chunks=[]
for sec in (contents,bytes(cd),bytes(e)):
    for off in range(0,len(sec),1<<20):
        ch=sec[off:off+(1<<20)]; chunks.append(hashlib.sha256(b'\xa5'+struct.pack('<I',len(ch))+ch).digest())
top=hashlib.sha256(b'\x5a'+struct.pack('<I',len(chunks))+b''.join(chunks)).digest()
assert top==digest, 'digest mismatch'
print('digest matches: True'); print('cert:', c.subject.rfc4514_string())
