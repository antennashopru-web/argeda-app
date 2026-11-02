import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Пересобирает zip: несжатые записи выравнивает на 4 байта (resources.arsc — на 4096), остальное сжимает. */
public class ZipAlign {
    public static void main(String[] a) throws Exception {
        Path in = Paths.get(a[0]), out = Paths.get(a[1]);
        List<String> names = new ArrayList<>(); Map<String, byte[]> data = new LinkedHashMap<>(); Map<String, Integer> method = new HashMap<>();
        try (ZipFile zf = new ZipFile(in.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                names.add(e.getName());
                data.put(e.getName(), zf.getInputStream(e).readAllBytes());
                method.put(e.getName(), e.getMethod());
            }
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        long offset = 0;
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (String n : names) {
                byte[] d = data.get(n);
                boolean stored = method.get(n) == ZipEntry.STORED || n.endsWith(".arsc") || n.endsWith(".png") || n.endsWith(".webp");
                ZipEntry e = new ZipEntry(n);
                e.setTime(1262304000000L);
                if (stored) {
                    e.setMethod(ZipEntry.STORED); e.setSize(d.length); e.setCompressedSize(d.length);
                    CRC32 crc = new CRC32(); crc.update(d); e.setCrc(crc.getValue());
                    int align = n.endsWith(".arsc") ? 4096 : 4;
                    int nameLen = n.getBytes("UTF-8").length;
                    long dataStart = offset + 30 + nameLen;
                    int pad = (int) ((align - (dataStart % align)) % align);
                    if (pad > 0 && pad < 4) pad += align;
                    if (pad > 0) e.setExtra(extraPad(pad));
                    zos.putNextEntry(e); zos.write(d); zos.closeEntry();
                    zos.flush();
                    offset = bos.size();
                    if (offset != dataStart + pad + d.length) throw new IllegalStateException("offset mismatch at " + n);
                } else {
                    e.setMethod(ZipEntry.DEFLATED);
                    zos.putNextEntry(e); zos.write(d); zos.closeEntry();
                    zos.flush();
                    offset = bos.size();
                }
            }
        }
        Files.write(out, bos.toByteArray());
        System.out.println("aligned: " + out);
    }
    static byte[] extraPad(int pad) {
        // Поле extra: id 0xd935 (как у zipalign), длина pad-4, затем нули
        byte[] x = new byte[pad];
        x[0] = (byte) 0x35; x[1] = (byte) 0xd9; x[2] = (byte) ((pad - 4) & 0xff); x[3] = (byte) (((pad - 4) >> 8) & 0xff);
        return x;
    }
}
