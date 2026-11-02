import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.Certificate;
import java.util.*;

/** Минимальная подпись APK по схеме v2 (RSA/SHA-256) штатными средствами JDK. */
public class ApkSignV2 {
    public static void main(String[] a) throws Exception {
        Path in = Paths.get(a[0]), out = Paths.get(a[1]);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream is = Files.newInputStream(Paths.get(a[2]))) { ks.load(is, a[3].toCharArray()); }
        String alias = a[4];
        PrivateKey key = (PrivateKey) ks.getKey(alias, a[3].toCharArray());
        Certificate cert = ks.getCertificate(alias);

        byte[] apk = Files.readAllBytes(in);
        ByteBuffer bb = ByteBuffer.wrap(apk).order(ByteOrder.LITTLE_ENDIAN);
        int eocd = findEocd(apk);
        int cdSize = bb.getInt(eocd + 12), cdOff = bb.getInt(eocd + 16);
        byte[] contents = Arrays.copyOfRange(apk, 0, cdOff);
        byte[] cd = Arrays.copyOfRange(apk, cdOff, cdOff + cdSize);
        byte[] eocdBytes = Arrays.copyOfRange(apk, eocd, apk.length);

        // Для дайджеста смещение каталога в EOCD считается указывающим на начало блока подписи (= старое cdOff)
        byte[] eocdForDigest = eocdBytes.clone();
        ByteBuffer.wrap(eocdForDigest).order(ByteOrder.LITTLE_ENDIAN).putInt(16, cdOff);
        byte[] digest = digest(contents, cd, eocdForDigest);
        byte[] sigBlock = buildSigningBlock(digest, key, cert);
        int newCdOff = cdOff + sigBlock.length;
        ByteBuffer.wrap(eocdBytes).order(ByteOrder.LITTLE_ENDIAN).putInt(16, newCdOff);
        try (OutputStream os = Files.newOutputStream(out)) {
            os.write(contents); os.write(sigBlock); os.write(cd); os.write(eocdBytes);
        }
        System.out.println("signed: " + out + " (" + Files.size(out) + " bytes)");
    }

    static int findEocd(byte[] apk) {
        for (int i = apk.length - 22; i >= 0; i--) {
            if (apk[i] == 0x50 && apk[i + 1] == 0x4b && apk[i + 2] == 0x05 && apk[i + 3] == 0x06) return i;
        }
        throw new IllegalStateException("EOCD not found");
    }

    static byte[] digest(byte[]... sections) throws Exception {
        List<byte[]> chunks = new ArrayList<>();
        for (byte[] s : sections) {
            for (int off = 0; off < s.length; off += 1 << 20) {
                int len = Math.min(1 << 20, s.length - off);
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                md.update((byte) 0xa5);
                md.update(le32(len));
                md.update(s, off, len);
                chunks.add(md.digest());
            }
        }
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update((byte) 0x5a);
        md.update(le32(chunks.size()));
        for (byte[] c : chunks) md.update(c);
        return md.digest();
    }

    static byte[] buildSigningBlock(byte[] digest, PrivateKey key, Certificate cert) throws Exception {
        int algo = 0x0103; // RSASSA-PKCS1-v1_5 с SHA2-256
        byte[] digests = lenPrefixed(lenPrefixed(concat(le32(algo), lenPrefixed(digest))));
        byte[] certs = lenPrefixed(lenPrefixed(cert.getEncoded()));
        byte[] attrs = lenPrefixed(new byte[0]);
        byte[] signedData = concat(digests, certs, attrs);

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(key);
        sig.update(signedData);
        byte[] signature = sig.sign();
        byte[] signatures = lenPrefixed(lenPrefixed(concat(le32(algo), lenPrefixed(signature))));
        byte[] pub = lenPrefixed(cert.getPublicKey().getEncoded());
        byte[] signer = lenPrefixed(concat(lenPrefixed(signedData), signatures, pub));
        byte[] v2 = lenPrefixed(signer); // значение пары id-value: последовательность подписантов (у нас один)

        byte[] pair = concat(le32(0x7109871a), v2);
        long pairLen = pair.length;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        long blockSize = 8 + pairLen + 8 + 16; // пара id-value, поле размера, магия; без первого поля размера
        b.write(le64(blockSize));
        b.write(le64(pairLen)); b.write(pair);
        b.write(le64(blockSize));
        b.write("APK Sig Block 42".getBytes("US-ASCII"));
        return b.toByteArray();
    }

    static byte[] lenPrefixed(byte[] d) { return concat(le32(d.length), d); }
    static byte[] le32(int v) { return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array(); }
    static byte[] le64(long v) { return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array(); }
    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] p : parts) b.write(p, 0, p.length);
        return b.toByteArray();
    }
}
